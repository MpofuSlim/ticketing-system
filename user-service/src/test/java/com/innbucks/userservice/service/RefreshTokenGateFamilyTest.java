package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RefreshToken;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.OrganizationException;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gate sign-in's scope (V57) rides the refresh ROW, like phone_proof: a
 * refresh re-derives the claims from the live user, so a gate family that lost
 * the flag on rotation would come back as the account's full session.
 */
class RefreshTokenGateFamilyTest {

    private UserRepository userRepo;
    private OrganizationService organizations;
    private RefreshTokenService service;
    private final Map<String, RefreshToken> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-test-test-test-test-test-test-test");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 86_400_000L);

        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        userRepo = mock(UserRepository.class);
        organizations = mock(OrganizationService.class);
        when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken row = inv.getArgument(0);
            store.put(row.getTokenHash(), row);
            return row;
        });
        when(repo.findByTokenHash(any())).thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        when(repo.revokeFamily(any(UUID.class), any(Instant.class))).thenReturn(0);

        service = new RefreshTokenService(repo, userRepo, jwtUtil);
        ReflectionTestUtils.setField(service, "organizationService", organizations);
    }

    private User tendai() {
        User u = User.builder().id(7L).phoneNumber("+263771234567").password("x").active(true).build();
        when(userRepo.findById(7L)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("a gate family is stamped gate-only AND phone-proof, bound to its device, with no organization")
    void gateFamily_isStamped() {
        User u = tendai();

        service.issueGateFamily(u, "device-1");

        RefreshToken row = store.values().iterator().next();
        assertThat(row.isGateOnly()).isTrue();
        assertThat(row.isPhoneProof()).isTrue();
        assertThat(row.getOrganizationId()).isNull();
        assertThat(row.getDeviceIdHash()).isNotNull();
        verifyNoInteractions(organizations);
    }

    @Test
    @DisplayName("every rotation carries gate-only onto the successor and back to the caller")
    void rotation_carriesGateOnly() {
        User u = tendai();
        String first = service.issueGateFamily(u, "device-1");

        RefreshTokenService.Rotation rotation = service.rotate(first, "device-1");
        RefreshTokenService.Rotation again = service.rotate(rotation.refreshToken(), "device-1");

        assertThat(rotation.gateOnly()).isTrue();
        assertThat(rotation.phoneProof()).isTrue();
        assertThat(again.gateOnly()).isTrue();
        assertThat(store.values()).hasSize(3).allMatch(RefreshToken::isGateOnly);
    }

    @Test
    @DisplayName("an ordinary or phone-proof family is never gate-only")
    void otherFamilies_areNotGateOnly() {
        User u = tendai();

        RefreshTokenService.Rotation password = service.rotate(service.issueNewFamily(u, "d"), "d");
        RefreshTokenService.Rotation phone = service.rotate(service.issueNewFamily(u, "d", true), "d");

        assertThat(password.gateOnly()).isFalse();
        assertThat(phone.gateOnly()).isFalse();
    }

    @Test
    @DisplayName("a gate session can't be moved into a business, and keeps working")
    void gateFamily_cannotSwitchOrganization() {
        User u = tendai();
        String first = service.issueGateFamily(u, "device-1");

        assertThatThrownBy(() -> service.rotateInto(first, "device-1", UUID.randomUUID()))
                .isInstanceOf(OrganizationException.class)
                .extracting(t -> ((OrganizationException) t).getErrorCode())
                .isEqualTo("organization_context_not_allowed");
        assertThat(store.values().iterator().next().getRevokedAt()).isNull();
    }
}
