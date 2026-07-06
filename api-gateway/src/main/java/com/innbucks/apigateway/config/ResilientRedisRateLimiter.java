package com.innbucks.apigateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.local.LocalBucket;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link RateLimiter} that keeps throttling the SMS-cost / payment routes
 * even when Redis is unreachable (OWASP A04 — insecure design / fail-safe
 * defaults).
 *
 * <p><b>Why this exists.</b> Spring Cloud Gateway's {@link RedisRateLimiter}
 * <b>fails OPEN</b>: when the Redis round-trip errors (outage, connection
 * refused, timeout) it swallows the error, logs {@code "Error determining if
 * user allowed from redis"} and returns {@code allowed=true}. For the high-cost
 * surfaces (SMS sends on {@code /auth/otp/**}, {@code /auth/forgot-password},
 * {@code /auth/register}, and the payment rail under {@code /payments/**}) that
 * silently removes the only per-IP throttle during exactly the window an
 * attacker would exploit to burn the SMS budget or hammer the payment API.
 *
 * <p><b>What it does.</b> It delegates to the real {@code RedisRateLimiter} on
 * the happy path (so Redis stays the shared, cross-instance source of truth),
 * and ONLY when it detects Redis's fail-open response does it fall back to a
 * per-instance in-memory <b>Bucket4j</b> token bucket that mirrors the SAME
 * replenishRate / burstCapacity the route already configured. The fallback
 * still ALLOWS traffic up to that configured rate — it is a fail-<b>safe</b>
 * bucket, not a blanket deny — so a Redis outage degrades to local throttling
 * instead of no throttling. (Login intentionally carries no limiter at all and
 * is never routed here, so a Redis outage can never lock users out mid-login.)
 *
 * <p><b>How the fail-open is detected.</b> {@code RedisRateLimiter} never
 * propagates the Redis error to the caller, so an {@code onErrorResume} alone
 * would not see the outage. Its fail-open path is however uniquely
 * identifiable: it returns {@code allowed=true} with the
 * {@code X-RateLimit-Remaining} header set to {@code "-1"} — a sentinel the
 * Lua-script (Redis-up) path never emits, where remaining is always
 * {@code >= 0}. So {@code allowed && remaining == -1} is treated as "Redis is
 * down, apply the local bucket". An {@code onErrorResume} is kept too, as
 * belt-and-suspenders for any code path that DOES surface the error. This
 * detection relies on the gateway keeping {@code include-headers} at its
 * default {@code true}; were headers disabled the sentinel would be absent and
 * this limiter simply behaves as today (fails open) — never worse.
 *
 * <p><b>Config source of truth.</b> The per-route replenish/burst values come
 * straight from the delegate {@code RedisRateLimiter}'s own bound config map
 * ({@link RedisRateLimiter#getConfig()}), which Spring Cloud Gateway populates
 * from the very same {@code redis-rate-limiter.*} route args — so there is one
 * source of truth and no duplicated env-var wiring.
 *
 * <p><b>Boundedness.</b> The fallback buckets live in a Caffeine cache capped
 * at {@code maxBuckets} entries with idle expiry, so a key-rotating attacker
 * (the bucket key is per-IP or per-token) cannot grow the map without bound
 * during an outage.
 *
 * <p>Registered with {@code autowireCandidate=false} so it does not compete
 * with the auto-configured {@code RedisRateLimiter} for the filter factory's
 * default {@code RateLimiter} injection; the SMS/payment routes reference it
 * explicitly by name via {@code rate-limiter: "#{@resilientRedisRateLimiter}"}.
 */
public class ResilientRedisRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

    private static final Log log = LogFactory.getLog(ResilientRedisRateLimiter.class);

    /** Remaining-token sentinel RedisRateLimiter emits on its fail-open path. */
    private static final String REDIS_FAIL_OPEN_REMAINING = "-1";

    private final RedisRateLimiter delegate;
    private final Cache<String, LocalBucket> fallbackBuckets;

    ResilientRedisRateLimiter(RedisRateLimiter delegate, long maxBuckets, Duration bucketIdleTtl) {
        this.delegate = delegate;
        this.fallbackBuckets = Caffeine.newBuilder()
                .maximumSize(maxBuckets)
                .expireAfterAccess(bucketIdleTtl)
                .build();
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        return delegate.isAllowed(routeId, id)
                .map(response -> redisFailedOpen(response) ? localDecision(routeId, id) : response)
                // Belt-and-suspenders: if a future/edge path DOES propagate the
                // Redis error instead of failing open, still throttle locally.
                .onErrorResume(error -> {
                    if (log.isWarnEnabled()) {
                        log.warn("Redis rate-limit call errored for route '" + routeId
                                + "'; applying in-memory fallback bucket", error);
                    }
                    return Mono.just(localDecision(routeId, id));
                });
    }

    private boolean redisFailedOpen(Response response) {
        return response.isAllowed()
                && REDIS_FAIL_OPEN_REMAINING.equals(
                        response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER));
    }

    /**
     * Charge one request against the per-{@code (routeId,id)} in-memory bucket
     * and shape a {@link Response} that mirrors RedisRateLimiter's headers.
     */
    private Response localDecision(String routeId, String id) {
        RedisRateLimiter.Config config = configFor(routeId);
        int replenishRate = config.getReplenishRate();
        long burstCapacity = config.getBurstCapacity();
        int requestedTokens = Math.max(1, config.getRequestedTokens());

        LocalBucket bucket = fallbackBuckets.get(routeId + "|" + id,
                key -> newBucket(replenishRate, burstCapacity));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(requestedTokens);

        Map<String, String> headers = new HashMap<>();
        headers.put(RedisRateLimiter.REMAINING_HEADER,
                String.valueOf(Math.max(0, probe.getRemainingTokens())));
        headers.put(RedisRateLimiter.REPLENISH_RATE_HEADER, String.valueOf(replenishRate));
        headers.put(RedisRateLimiter.BURST_CAPACITY_HEADER, String.valueOf(burstCapacity));
        headers.put(RedisRateLimiter.REQUESTED_TOKENS_HEADER, String.valueOf(requestedTokens));
        return new Response(probe.isConsumed(), headers);
    }

    /**
     * A greedy-refill token bucket: starts full at {@code burstCapacity} and
     * refills {@code replenishRate} tokens/second — the same steady-state and
     * burst semantics RedisRateLimiter enforces via its Lua script.
     */
    private static LocalBucket newBucket(int replenishRate, long burstCapacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(burstCapacity)
                .refillGreedy(replenishRate, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Per-route config bound by Spring Cloud Gateway from the route's
     * {@code redis-rate-limiter.*} args (shared with the delegate). Defensive
     * fallback to a tight bucket if a route were ever wired here without config
     * — throttle rather than silently allow. Unreachable for the routes we wire,
     * which all declare explicit replenish/burst args.
     */
    private RedisRateLimiter.Config configFor(String routeId) {
        RedisRateLimiter.Config config = delegate.getConfig().get(routeId);
        if (config == null) {
            log.warn("No rate-limit config bound for route '" + routeId
                    + "'; using a conservative 1 rps / 3 burst local bucket");
            config = new RedisRateLimiter.Config().setReplenishRate(1).setBurstCapacity(3);
        }
        return config;
    }

    // --- StatefulConfigurable: share the delegate's config so SCG's arg
    //     binding and our fallback reads use a single source of truth. ---

    @Override
    public Map<String, RedisRateLimiter.Config> getConfig() {
        return delegate.getConfig();
    }

    @Override
    public Class<RedisRateLimiter.Config> getConfigClass() {
        return delegate.getConfigClass();
    }

    @Override
    public RedisRateLimiter.Config newConfig() {
        return delegate.newConfig();
    }
}
