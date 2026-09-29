package com.innbucks.userservice.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

/**
 * Publishes each user's current JWT {@code token_version} to the SHARED Redis
 * so downstream services (payment / seat / booking / ...) can enforce session
 * supersession (OWASP A07 / CWE-613) without a per-request call back into
 * user-service.
 *
 * <p>user-service owns {@code users.token_version} in Postgres and its own
 * {@link JwtFilter} already compares a token's {@code tokenVersion} claim
 * against that column. Downstream services can't see our Postgres — they read
 * this Redis entry instead. Every path that bumps {@code token_version} does so
 * through {@code TokenVersionBumper}, which calls
 * {@link #publishAfterCommit(UUID, long)} so the shared view only ever carries
 * a version Postgres has committed.
 *
 * <p><b>Contract (must match the downstream read side exactly):</b>
 * <pre>auth:tokenver:&lt;userUuid&gt; -&gt; "&lt;token_version&gt;"</pre>
 * where {@code <userUuid>} is the SAME canonical hyphenated-lowercase
 * {@link UUID#toString()} value user-service stamps into the JWT
 * {@code userUuid} claim (see {@link JwtUtil#generateToken}), and the value is
 * the {@code token_version} as a decimal String.
 *
 * <p><b>Best-effort, fail-open</b> — mirrors {@link
 * com.innbucks.userservice.service.TokenRevocationService}'s shared-denylist
 * publish. Postgres stays the source of truth; a Redis outage must never fail
 * the bump flow. Downstream simply fails open for the affected user until Redis
 * recovers (the short access-token TTL is the backstop), which is no worse than
 * the pre-feature behaviour where downstream never saw the version at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenVersionPublisher {

    /**
     * Redis key prefix for the cross-service session-version view. The full key
     * is {@code auth:tokenver:<userUuid>} and the value is the user's current
     * {@code users.token_version} as a decimal String. Downstream services read
     * {@code <prefix><userUuid-from-JWT-claim>} and reject any access token
     * whose {@code tokenVersion} claim doesn't match the published value.
     */
    public static final String SHARED_TOKEN_VERSION_PREFIX = "auth:tokenver:";

    /** Fallback TTL used only if the configured refresh lifetime is non-positive
     *  (misconfiguration) — keeps us from ever calling Redis SET with a
     *  zero/negative expiry, which would throw and drop the publish. */
    private static final Duration FALLBACK_TTL = Duration.ofDays(30);

    /**
     * Counter name for a publish Redis refused. Alerted: while it is climbing,
     * downstream services keep accepting access tokens user-service has already
     * ended (until each expires, at most the access-token TTL).
     */
    public static final String PUBLISH_FAILED_METRIC = "user.tokenver.publish_failed";

    private final StringRedisTemplate redis;

    /**
     * Field-injected (optional) so the existing single-argument construction in
     * {@code TokenVersionPublisherTest} still compiles; null there means the
     * failure is logged but not counted.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * Entry TTL, reusing the refresh-token lifetime (milliseconds) so any
     * outstanding access token — whose life is capped by the refresh window —
     * is comfortably covered before the entry expires. Same knob {@link JwtUtil}
     * mints refresh tokens with ({@code jwt.refresh-expiration}, default 7 days).
     */
    @Value("${jwt.refresh-expiration}")
    private long refreshExpirationMs;

    /**
     * Publish {@code userUuid -> version} to the shared Redis.
     *
     * <p>No-op when {@code userUuid} is null: legacy tokens carry no
     * {@code userUuid} claim, so there's nothing downstream can key on —
     * downstream fails open for them, which is an accepted, no-regression
     * limitation. Never throws: a Redis failure is logged and swallowed so the
     * caller's DB bump (the source of truth) still commits.
     */
    public void publish(UUID userUuid, long version) {
        if (userUuid == null) {
            // Legacy caller minted a token without a userUuid claim — nothing
            // downstream can key on, so skip the publish (fail open there).
            return;
        }
        // UUID#toString is the canonical hyphenated-lowercase form — byte-for-byte
        // the same string put in the JWT userUuid claim, so the downstream lookup
        // key lines up exactly.
        String key = SHARED_TOKEN_VERSION_PREFIX + userUuid;
        Duration ttl = refreshExpirationMs > 0 ? Duration.ofMillis(refreshExpirationMs) : FALLBACK_TTL;
        try {
            redis.opsForValue().set(key, Long.toString(version), ttl);
        } catch (RuntimeException ex) {
            // Fail open: Postgres (users.token_version) stays the source of truth
            // for user-service's own JwtFilter; downstream keeps the short
            // access-token TTL as a backstop until Redis recovers.
            log.warn("Failed to publish token version to shared Redis key={} version={}; "
                    + "downstream relies on access-token TTL until Redis recovers", key, version, ex);
            countFailure();
        }
    }

    /**
     * Publish {@code userUuid -> version} once the surrounding transaction has
     * COMMITTED — the only way a bump site should publish.
     *
     * <p>Publishing inside the transaction (what {@code setRoles} used to do)
     * lets Redis get ahead of Postgres: if the transaction then rolls back, every
     * downstream service rejects the user's still-valid tokens while
     * user-service accepts them. After commit, the published value is always one
     * Postgres actually holds, and a rolled-back bump publishes nothing.
     *
     * <p>With no transaction synchronization active (a plain unit test, or a
     * caller outside any transaction) there is nothing to wait for, so it
     * publishes immediately. Same fail-open contract as {@link #publish}.
     */
    public void publishAfterCommit(UUID userUuid, long version) {
        if (userUuid == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(userUuid, version);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(userUuid, version);
            }
        });
    }

    private void countFailure() {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(PUBLISH_FAILED_METRIC).increment();
        } catch (RuntimeException ignored) {
            // A metrics failure must never become a publish failure.
        }
    }
}
