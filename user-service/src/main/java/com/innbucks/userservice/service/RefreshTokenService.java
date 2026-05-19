package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RefreshToken;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>Each call to {@link #rotate(String)} consumes the supplied refresh token,
 * marks it revoked, and issues a fresh one linked to the same family. If the
 * caller presents a token that has already been rotated (revoked_at is set),
 * we treat that as a token-theft signal and revoke every refresh token in
 * that family — both the legitimate client and the attacker are forced to
 * re-authenticate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public static class ReuseDetectedException extends RuntimeException {
        public ReuseDetectedException(String msg) { super(msg); }
    }

    /** Result of a rotation: the new refresh token (raw JWT) and the user it belongs to. */
    public record Rotation(User user, String refreshToken) {}

    /**
     * Mints the first refresh token of a new family for {@code user}. Called on login.
     * The {@code deviceId} is the raw {@code X-Device-Id} header from the
     * FE — it's hashed before storage so a database leak doesn't expose
     * device identifiers in plaintext. Pass {@code null} when the caller
     * didn't send the header (legacy FE during rollout); the row stores
     * a null device_id_hash and the future {@link #rotate} won't enforce
     * device binding on it.
     */
    @Transactional
    public String issueNewFamily(User user, String deviceId) {
        UUID familyId = UUID.randomUUID();
        return mint(user, familyId, null, hashOrNull(deviceId));
    }

    /**
     * Validates the supplied refresh token, rotates it, and returns the user
     * along with a freshly-minted refresh token. The supplied token is marked
     * revoked and cannot be reused.
     *
     * <p>If the stored row carries a {@code device_id_hash} (i.e. the
     * issuing login sent {@code X-Device-Id}), the rotate request MUST
     * present the same device id — mismatch is treated as token theft
     * and fires the same family-revoke side effect as a replayed token.
     * Rows without a stored hash (legacy sessions from before the
     * rollout) skip the check and rotate without device enforcement.
     *
     * @throws ReuseDetectedException if the token has already been
     *         rotated OR the device-id doesn't match. Family revoked
     *         in both cases.
     */
    // noRollbackFor: ReuseDetectedException is part of the security contract —
    // when we detect token replay we WANT the side-effect (revokeFamily) to
    // commit. Without this, Spring would roll back the family-revocation
    // UPDATE and an attacker could keep replaying the stolen token.
    @Transactional(noRollbackFor = ReuseDetectedException.class)
    public Rotation rotate(String rawToken, String deviceId) {
        if (rawToken == null || rawToken.isBlank() || !jwtUtil.isTokenValid(rawToken)) {
            throw new RuntimeException("Invalid or expired refresh token");
        }
        if (!jwtUtil.isRefreshToken(rawToken)) {
            throw new RuntimeException("Not a refresh token");
        }

        String hash = sha256(rawToken);
        RefreshToken row = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new RuntimeException("Refresh token not recognised"));

        if (row.getRevokedAt() != null) {
            // Replay of an already-rotated token — treat as theft.
            int killed = refreshTokenRepository.revokeFamily(row.getFamilyId(), Instant.now());
            log.warn("Refresh-token reuse detected familyId={} rowsRevoked={}", row.getFamilyId(), killed);
            throw new ReuseDetectedException("Refresh token reuse detected; family revoked");
        }
        // Device-binding check. Skipped only when the row was minted
        // before the rollout (NULL stored hash). Once a row carries a
        // hash, the request MUST present a matching X-Device-Id — same
        // family-revoke side effect as replay detection above.
        if (row.getDeviceIdHash() != null) {
            String requestHash = hashOrNull(deviceId);
            if (requestHash == null || !row.getDeviceIdHash().equals(requestHash)) {
                int killed = refreshTokenRepository.revokeFamily(row.getFamilyId(), Instant.now());
                log.warn("Refresh-token device mismatch familyId={} rowsRevoked={} hasHeader={}",
                        row.getFamilyId(), killed, requestHash != null);
                throw new ReuseDetectedException("Refresh token reuse detected; family revoked");
            }
        }
        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw new RuntimeException("Refresh token expired");
        }

        User user = userRepository.findById(row.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Preserve the device binding through the rotation chain. A
        // family stays bound to whatever device its first token was
        // minted for — a customer can't sneak their session onto a new
        // device just by refreshing.
        String newToken = mint(user, row.getFamilyId(), row.getId(), row.getDeviceIdHash());

        // Mark the consumed token as revoked and chained to its replacement.
        RefreshToken successor = refreshTokenRepository.findByTokenHash(sha256(newToken))
                .orElseThrow(() -> new IllegalStateException("Successor row not found"));
        row.setRevokedAt(Instant.now());
        row.setReplacedById(successor.getId());
        refreshTokenRepository.save(row);

        return new Rotation(user, newToken);
    }

    /** Revokes every active refresh token in the family that owns {@code rawToken}. */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .ifPresent(row -> refreshTokenRepository.revokeFamily(row.getFamilyId(), Instant.now()));
    }

    @Scheduled(fixedDelayString = "PT6H")
    @Transactional
    public void purgeExpired() {
        int removed = refreshTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired refresh-token rows", removed);
        }
    }

    private String mint(User user, UUID familyId, UUID parentId, String deviceIdHash) {
        String subject = user.getEmail() != null ? user.getEmail() : user.getPhoneNumber();
        String raw = jwtUtil.generateRefreshToken(subject);
        RefreshToken row = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .tokenHash(sha256(raw))
                .familyId(familyId)
                .parentId(parentId)
                .deviceIdHash(deviceIdHash)
                .expiresAt(jwtUtil.extractExpiration(raw).toInstant())
                .createdAt(Instant.now())
                .build();
        refreshTokenRepository.save(row);
        return raw;
    }

    private static String hashOrNull(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        return sha256(deviceId);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
