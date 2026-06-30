package com.innbucks.userservice.service;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the trusted-device ("remember this device") primitives:
 * token minting + hash-at-rest, the constant-time live-token check, scoping to
 * (user, device), expiry handling, and bulk trust revocation.
 */
class DeviceTrustServiceTest {

    private DeviceRepository deviceRepository;
    private DeviceTrustService service;

    private static final long USER_ID = 42L;
    private static final String DEVICE_ID = "device-abc";

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        MfaProperties props = new MfaProperties();
        props.setTrustedDeviceDays(30);
        service = new DeviceTrustService(deviceRepository, props);
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user(long id) {
        return User.builder().id(id).email("alice@example.com").build();
    }

    // ---- trustDevice ---------------------------------------------------------

    @Test
    void trustDevice_persistsHash_andFutureExpiry_andReturnsRawToken() {
        User u = user(USER_ID);
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, DEVICE_ID)).thenReturn(Optional.empty());

        DeviceTrustService.TrustGrant grant = service.trustDevice(u, DEVICE_ID);

        assertThat(grant.rawToken()).isNotBlank();
        assertThat(grant.trustedUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC));

        ArgumentCaptor<Device> saved = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(saved.capture());
        Device row = saved.getValue();
        // A brand-new device row is created for (user, device) when none exists.
        assertThat(row.getUser()).isSameAs(u);
        assertThat(row.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(row.getMfaTrustedUntil()).isEqualTo(grant.trustedUntil());
        // CRITICAL: the persisted value is the HASH, never the raw token.
        assertThat(row.getMfaTrustTokenHash()).isNotNull();
        assertThat(row.getMfaTrustTokenHash()).isNotEqualTo(grant.rawToken());
        assertThat(row.getMfaTrustTokenHash()).isEqualTo(DeviceTrustService.sha256(grant.rawToken()));
    }

    @Test
    void trustDevice_upsertsOntoExistingDeviceRow() {
        User u = user(USER_ID);
        Device existing = Device.builder().id(7L).user(u).deviceId(DEVICE_ID).platform("ios").build();
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, DEVICE_ID)).thenReturn(Optional.of(existing));

        DeviceTrustService.TrustGrant grant = service.trustDevice(u, DEVICE_ID);

        // Reuses the same row (keeps other push fields), just stamps trust.
        assertThat(existing.getId()).isEqualTo(7L);
        assertThat(existing.getPlatform()).isEqualTo("ios");
        assertThat(existing.getMfaTrustTokenHash()).isEqualTo(DeviceTrustService.sha256(grant.rawToken()));
        verify(deviceRepository).save(existing);
    }

    @Test
    void trustDevice_mintsDistinctHighEntropyTokensEachCall() {
        User u = user(USER_ID);
        when(deviceRepository.findByUserIdAndDeviceId(any(), any())).thenReturn(Optional.empty());

        String t1 = service.trustDevice(u, DEVICE_ID).rawToken();
        String t2 = service.trustDevice(u, DEVICE_ID).rawToken();

        assertThat(t1).isNotEqualTo(t2);
        // 32 random bytes Base64URL (no padding) → 43 chars.
        assertThat(t1).hasSize(43);
        assertThat(t1).doesNotContain("=", "+", "/");
    }

    // ---- isTrusted -----------------------------------------------------------

    @Test
    void isTrusted_matchingNonExpiredToken_returnsTrue() {
        String raw = mintTrustedRow(LocalDateTime.now(ZoneOffset.UTC).plusDays(10));
        assertThat(service.isTrusted(USER_ID, DEVICE_ID, raw)).isTrue();
    }

    @Test
    void isTrusted_wrongToken_returnsFalse() {
        mintTrustedRow(LocalDateTime.now(ZoneOffset.UTC).plusDays(10));
        assertThat(service.isTrusted(USER_ID, DEVICE_ID, "not-the-token")).isFalse();
    }

    @Test
    void isTrusted_expiredUntil_returnsFalse() {
        String raw = mintTrustedRow(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThat(service.isTrusted(USER_ID, DEVICE_ID, raw)).isFalse();
    }

    @Test
    void isTrusted_unknownDevice_returnsFalse() {
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, "ghost-device")).thenReturn(Optional.empty());
        assertThat(service.isTrusted(USER_ID, "ghost-device", "anything")).isFalse();
    }

    @Test
    void isTrusted_deviceWithNoTrustHash_returnsFalse() {
        // A device that was registered (e.g. tier-3) but never trusted carries null hash/until.
        Device row = Device.builder().user(user(USER_ID)).deviceId(DEVICE_ID).build();
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, DEVICE_ID)).thenReturn(Optional.of(row));
        assertThat(service.isTrusted(USER_ID, DEVICE_ID, "anything")).isFalse();
    }

    @Test
    void isTrusted_blankInputs_returnFalse() {
        assertThat(service.isTrusted(USER_ID, DEVICE_ID, "  ")).isFalse();
        assertThat(service.isTrusted(USER_ID, "  ", "token")).isFalse();
        assertThat(service.isTrusted(null, DEVICE_ID, "token")).isFalse();
    }

    @Test
    void isTrusted_isScopedToUserAndDevice() {
        // A trust row for (USER_ID, DEVICE_ID) must not satisfy a different user
        // or a different device — the lookup is keyed on the exact pair.
        String raw = mintTrustedRow(LocalDateTime.now(ZoneOffset.UTC).plusDays(10));

        // Different user: repo returns empty for (99, DEVICE_ID).
        when(deviceRepository.findByUserIdAndDeviceId(99L, DEVICE_ID)).thenReturn(Optional.empty());
        assertThat(service.isTrusted(99L, DEVICE_ID, raw)).isFalse();

        // Different device: repo returns empty for (USER_ID, other-device).
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, "other-device")).thenReturn(Optional.empty());
        assertThat(service.isTrusted(USER_ID, "other-device", raw)).isFalse();
    }

    // ---- clearTrustForUser ---------------------------------------------------

    @Test
    void clearTrustForUser_nullsHashAndUntil_onTrustedDevices() {
        Device trusted = Device.builder().id(1L).user(user(USER_ID)).deviceId("d1")
                .mfaTrustTokenHash("somehash")
                .mfaTrustedUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(5))
                .build();
        Device untrusted = Device.builder().id(2L).user(user(USER_ID)).deviceId("d2").build();
        when(deviceRepository.findByUserId(USER_ID)).thenReturn(List.of(trusted, untrusted));

        service.clearTrustForUser(USER_ID);

        assertThat(trusted.getMfaTrustTokenHash()).isNull();
        assertThat(trusted.getMfaTrustedUntil()).isNull();
        // Only the device that actually carried trust is written back.
        verify(deviceRepository).save(trusted);
        verify(deviceRepository, never()).save(untrusted);
    }

    /**
     * Stub the repo so (USER_ID, DEVICE_ID) resolves to a device trusted with a
     * freshly-minted token expiring at {@code until}. Returns the raw token.
     */
    private String mintTrustedRow(LocalDateTime until) {
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, DEVICE_ID)).thenReturn(Optional.empty());
        DeviceTrustService.TrustGrant grant = service.trustDevice(user(USER_ID), DEVICE_ID);
        Device row = Device.builder().user(user(USER_ID)).deviceId(DEVICE_ID)
                .mfaTrustTokenHash(DeviceTrustService.sha256(grant.rawToken()))
                .mfaTrustedUntil(until)
                .build();
        when(deviceRepository.findByUserIdAndDeviceId(USER_ID, DEVICE_ID)).thenReturn(Optional.of(row));
        return grant.rawToken();
    }
}
