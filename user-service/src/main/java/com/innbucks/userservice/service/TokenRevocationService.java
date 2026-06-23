package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.RevokedToken;
import com.innbucks.userservice.repository.RevokedTokenRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRevocationService {

    /**
     * Redis key prefix for the cross-service logout denylist. user-service
     * publishes {@code <prefix><sha256HexLower(token)> -> "1"} here on revoke
     * so the downstream services (payment/seat/booking) that share this Redis
     * can reject an explicitly logged-out token immediately, instead of waiting
     * out the access-token TTL. Postgres ({@code revoked_tokens}) stays
     * user-service's own source of truth; this publish is best-effort.
     */
    public static final String SHARED_DENYLIST_PREFIX = "auth:revoked:";

    private final RevokedTokenRepository revokedTokenRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    @Transactional
    public void revoke(String token) {
        if (!jwtUtil.isTokenValid(token)) {
            throw new RuntimeException("Cannot revoke an invalid or expired token");
        }
        String hash = hash(token);
        if (revokedTokenRepository.existsByTokenHash(hash)) {
            log.debug("Token already revoked, skipping");
            return;
        }
        RevokedToken entry = RevokedToken.builder()
                .tokenHash(hash)
                .subject(jwtUtil.extractEmail(token))
                .expiresAt(jwtUtil.extractExpiration(token).toInstant())
                .revokedAt(Instant.now())
                .build();
        revokedTokenRepository.save(entry);
        log.info("Token revoked subject={}", entry.getSubject());

        // Best-effort publish to the shared Redis denylist so payment/seat/
        // booking honour the logout immediately. TTL = remaining token life;
        // a token at/past expiry needs no entry (the readers reject it on
        // their own TTL check). Never fail the logout if Redis is down —
        // Postgres above is our source of truth and the downstream services
        // still have the short access-token TTL as a backstop.
        Duration ttl = Duration.between(Instant.now(), entry.getExpiresAt());
        if (!ttl.isZero() && !ttl.isNegative()) {
            try {
                redis.opsForValue().set(SHARED_DENYLIST_PREFIX + hash, "1", ttl);
            } catch (RuntimeException ex) {
                log.warn("Failed to publish revoked token to shared Redis denylist subject={}; "
                        + "downstream relies on access-token TTL until it expires", entry.getSubject(), ex);
            }
        }
    }

    public boolean isRevoked(String token) {
        return revokedTokenRepository.existsByTokenHash(hash(token));
    }

    /**
     * True when the JWT's {@code tokenVersion} claim matches the user's
     * current {@code users.token_version} value (single-active-session
     * gate). Returns false on a stale token AND on a token whose subject
     * doesn't resolve to a user — the latter is treated as "session ended"
     * rather than "user not found" so the filter response stays uniform.
     */
    @Transactional(readOnly = true)
    public boolean isTokenVersionCurrent(String subject, long tokenVersion) {
        if (subject == null || subject.isBlank()) return false;
        Optional<Long> current = userRepository.findTokenVersionBySubject(subject);
        return current.map(v -> v == tokenVersion).orElse(false);
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        int removed = revokedTokenRepository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired revoked-token entries", removed);
        }
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
