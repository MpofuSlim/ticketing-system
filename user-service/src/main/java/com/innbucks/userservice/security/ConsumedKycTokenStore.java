package com.innbucks.userservice.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Enforces single-use of the customer KYC-upgrade verification token. The token
 * is a stateless JWT, so on its own nothing stops the same valid token from
 * driving the terminal tier-4 "mark verified" step repeatedly within its TTL.
 * This store closes that replay window: the tier-4 step records the token's
 * {@code jti} the first time it is consumed, and a later attempt with the same
 * {@code jti} hits the primary-key conflict and is rejected as
 * {@link KycVerificationTokenException.Reason#REPLAYED}.
 *
 * <p>The INSERT goes via {@link JdbcTemplate} directly (not a JPA save) so the
 * PK conflict surfaces as a catchable {@link DuplicateKeyException} — mirrors
 * {@code RefreshTokenService.persist} and the Oradian
 * {@code ConsumedVerificationTokenStore}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumedKycTokenStore {

    private static final String INSERT_SQL =
            "INSERT INTO consumed_kyc_verification_token (jti, consumed_at, expires_at) VALUES (?, ?, ?)";
    private static final String PRUNE_SQL =
            "DELETE FROM consumed_kyc_verification_token WHERE expires_at < ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Records {@code jti} as consumed. The first call for a given {@code jti}
     * succeeds; any subsequent call collides on the primary key and throws
     * {@link KycVerificationTokenException} (REPLAYED).
     *
     * <p>Call inside the tier-4 transaction so the consume row and the
     * {@code verified=true} flip commit atomically — the {@code jti} is marked
     * consumed iff the upgrade succeeds, and a later replay then fails.
     *
     * @param jti       the token's JWT ID (UUID string)
     * @param expiresAt the token's {@code exp} — when the row becomes prunable
     */
    public void consume(String jti, Instant expiresAt) {
        try {
            jdbcTemplate.update(INSERT_SQL,
                    UUID.fromString(jti),
                    Timestamp.from(Instant.now()),
                    Timestamp.from(expiresAt));
        } catch (DuplicateKeyException ex) {
            throw new KycVerificationTokenException(
                    KycVerificationTokenException.Reason.REPLAYED, "verification token already used");
        }
    }

    /**
     * Hourly prune of consumed-token rows whose {@code expires_at} has passed.
     * Once a token's own {@code exp} is in the past the verifier rejects it on
     * the expiry check anyway, so the consume row is dead weight for replay
     * defence and only bloats the table.
     */
    @Scheduled(cron = "${innbucks.auth.consumed-kyc-token-prune-cron:0 25 * * * *}")
    public void pruneExpired() {
        int deleted = jdbcTemplate.update(PRUNE_SQL, Timestamp.from(Instant.now()));
        if (deleted > 0) {
            log.info("Pruned {} consumed_kyc_verification_token row(s)", deleted);
        }
    }
}
