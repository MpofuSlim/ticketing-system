package com.innbucks.userservice.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
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
 * <p><b>Never moves backwards.</b> Each bump publishes from its own
 * after-commit callback, so two bumps of one account can reach Redis in the
 * opposite order to their commits: a login commits v+1, a deactivation commits
 * v+2, and the login thread's write — delayed by Redis latency or a GC pause —
 * lands last. A plain {@code SET} would then leave v+1 published, and every
 * downstream service would accept the login's access token for a deactivated
 * account until it expired. The write is therefore a server-side
 * compare-and-set ({@link #PUBLISH_IF_NOT_OLDER_LUA}): it stores the value only
 * when it is not lower than the one already there. Versions only ever grow in
 * Postgres, so the highest value seen is always the live one — with one
 * operational exception: after restoring user-service's database to an earlier
 * point, delete {@code auth:tokenver:*}, or the restored (lower) versions can
 * never replace the published ones and downstream refuses those users until
 * their versions overtake.
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

    /**
     * Write {@code ARGV[1]} to {@code KEYS[1]} with a {@code PX ARGV[2]} TTL
     * unless the stored value is HIGHER; returns 1 when written, 0 when a newer
     * version was already there. A missing or non-numeric stored value counts
     * as -1 (always overwritten). An equal value is rewritten, which only
     * refreshes the TTL. Atomic: Redis runs a script without interleaving.
     */
    static final String PUBLISH_IF_NOT_OLDER_LUA = """
            local current = tonumber(redis.call('GET', KEYS[1]) or '') or -1
            if tonumber(ARGV[1]) >= current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            return 0
            """;

    private static final RedisScript<Long> PUBLISH_IF_NOT_OLDER =
            new DefaultRedisScript<>(PUBLISH_IF_NOT_OLDER_LUA, Long.class);

    private final StringRedisTemplate redis;

    /**
     * Null when no registry is wired (the single-argument construction in
     * {@code TokenVersionPublisherTest}): failures are then logged but not counted.
     */
    private Counter publishFailed;

    /**
     * Registers {@link #PUBLISH_FAILED_METRIC} at ZERO as soon as the registry
     * is injected, not on the first failure. {@code increase()} cannot see a
     * series' first sample, so a counter created by the failure it counts
     * starts at 1 and the alert misses exactly the case it exists for — one
     * failed publish after a pod start, i.e. one deactivation during a Redis
     * blip. Same reason {@code SecurityMetrics} registers its alerted counters
     * up front.
     */
    @Autowired(required = false)
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.publishFailed = meterRegistry == null ? null
                : Counter.builder(PUBLISH_FAILED_METRIC)
                        .description("token_version publishes the shared Redis refused")
                        .register(meterRegistry);
    }

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
     * <p>Never lowers the published value (see the class note): a version older
     * than the one already there is dropped, which is how an out-of-order
     * after-commit publish loses to the newer one.
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
            Long written = redis.execute(PUBLISH_IF_NOT_OLDER, List.of(key),
                    Long.toString(version), Long.toString(ttl.toMillis()));
            if (written != null && written == 0L) {
                // A newer bump already published — this callback arrived late.
                log.info("Token version publish superseded by a newer one key={} version={}", key, version);
            }
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
        if (publishFailed == null) {
            return;
        }
        try {
            publishFailed.increment();
        } catch (RuntimeException ignored) {
            // A metrics failure must never become a publish failure.
        }
    }
}
