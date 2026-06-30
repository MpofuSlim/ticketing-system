package com.innbucks.userservice.service;

import com.innbucks.userservice.config.MfaProperties;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * "Remember this device" — the trusted-device 2FA bypass.
 *
 * <p>SECURITY-SENSITIVE: this is a deliberate second-factor bypass, so the
 * primitives are conservative and mirror {@link RefreshTokenService}:
 * <ul>
 *   <li><b>High-entropy token</b> — 32 bytes from {@link SecureRandom},
 *       Base64URL-encoded without padding. Returned to the client exactly once
 *       at step-2 of an MFA login ({@link #trustDevice}).</li>
 *   <li><b>Hash at rest</b> — only the SHA-256 (hex) hash lands in
 *       {@code devices.mfa_trust_token_hash}, exactly like
 *       {@code refresh_tokens.token_hash}. A DB leak can't be replayed as a
 *       live bypass.</li>
 *   <li><b>Constant-time compare</b> — the step-1 check
 *       ({@link #isTrusted}) hashes the presented token and compares the two
 *       hashes with {@link MessageDigest#isEqual} so the match is not timing-
 *       observable.</li>
 *   <li><b>Time-boxed</b> — trust is honoured only while
 *       {@code mfa_trusted_until} is in the future
 *       ({@link MfaProperties#getTrustedDeviceDays()} days from issue).</li>
 *   <li><b>Scoped to (user, device)</b> — the lookup is keyed on
 *       {@code (userId, deviceId)} so a token minted for one user/device can
 *       never satisfy another.</li>
 * </ul>
 *
 * <p>A mismatched / expired / unknown token is NOT an error here —
 * {@link #isTrusted} just returns false and the caller falls through to the
 * normal MFA challenge.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceTrustService {

    /** Entropy of the trust token, in bytes, before Base64URL encoding. */
    private static final int TOKEN_BYTES = 32;

    private final DeviceRepository deviceRepository;
    private final MfaProperties mfaProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Mints a fresh trust token for {@code (user, deviceId)}, persists its hash +
     * expiry on the device row (creating the row if this device has never been
     * seen), and returns the RAW token so the caller can hand it to the client
     * once. Upserts onto the existing per-(user, device) row.
     *
     * @return the raw token + its UTC expiry; the caller surfaces both on the
     *         step-2 response and never persists the raw token itself.
     */
    @Transactional
    public TrustGrant trustDevice(User user, String deviceId) {
        String rawToken = generateToken();
        LocalDateTime trustedUntil = LocalDateTime.now(ZoneOffset.UTC)
                .plusDays(mfaProperties.getTrustedDeviceDays());

        Device device = deviceRepository.findByUserIdAndDeviceId(user.getId(), deviceId)
                .orElseGet(() -> Device.builder()
                        .user(user)
                        .deviceId(deviceId)
                        .build());
        device.setMfaTrustTokenHash(sha256(rawToken));
        device.setMfaTrustedUntil(trustedUntil);
        deviceRepository.save(device);

        log.info("Device trusted for MFA bypass userId={} trustedUntil={}", user.getId(), trustedUntil);
        return new TrustGrant(rawToken, trustedUntil);
    }

    /**
     * True iff {@code presentedToken} is a live trust token for
     * {@code (userId, deviceId)} — the device row exists, carries a hash that
     * matches the presented token (constant-time), and {@code mfa_trusted_until}
     * is still in the future. False (never throws) on any miss so the caller can
     * fall through to the normal MFA challenge.
     */
    @Transactional(readOnly = true)
    public boolean isTrusted(Long userId, String deviceId, String presentedToken) {
        if (userId == null || deviceId == null || deviceId.isBlank()
                || presentedToken == null || presentedToken.isBlank()) {
            return false;
        }
        return deviceRepository.findByUserIdAndDeviceId(userId, deviceId)
                .filter(d -> d.getMfaTrustTokenHash() != null && d.getMfaTrustedUntil() != null)
                .filter(d -> d.getMfaTrustedUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
                .filter(d -> constantTimeEquals(d.getMfaTrustTokenHash(), sha256(presentedToken)))
                .isPresent();
    }

    /**
     * Clears trust on every device owned by {@code userId} (hash + until set to
     * NULL). Security hygiene: called on password change and on MFA
     * disable/reset so a credential change leaves no standing 2FA bypass behind.
     */
    @Transactional
    public void clearTrustForUser(Long userId) {
        List<Device> devices = deviceRepository.findByUserId(userId);
        int cleared = 0;
        for (Device device : devices) {
            if (device.getMfaTrustTokenHash() != null || device.getMfaTrustedUntil() != null) {
                device.setMfaTrustTokenHash(null);
                device.setMfaTrustedUntil(null);
                deviceRepository.save(device);
                cleared++;
            }
        }
        if (cleared > 0) {
            log.info("Cleared device trust userId={} devicesCleared={}", userId, cleared);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Constant-time compare of two hash strings. Both are SHA-256 hex digests of
     * equal length, so the byte arrays are equal-length and
     * {@link MessageDigest#isEqual} (constant-time since JDK 6u17) doesn't leak
     * the match position via timing.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 hex digest — same hashing {@link RefreshTokenService} uses for token_hash. */
    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Raw trust token (returned to the client once) + its UTC expiry. */
    public record TrustGrant(String rawToken, LocalDateTime trustedUntil) {
    }
}
