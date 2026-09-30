package com.innbucks.userservice.support;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.innbucks.userservice.exception.SupportPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Per-agent lookup limit (design §3.1.3, D11): a SLIDING window keyed by the
 * agent's {@code userUuid} — by default 60 calls per 10 minutes and 400 per
 * rolling 24 hours. Searches, detail reads, writes and the call-center reads of
 * {@code GET /admin/device-security/**} all count. Over either limit is
 * {@code 429 lookup_rate_limited} with {@code Retry-After}.
 *
 * <p>Why a limit on staff at all: an agent's account is the most valuable one to
 * steal on this platform — it reads any customer. The limit turns "export every
 * customer" into weeks of 429s and an alert, and it bounds a curious insider.
 *
 * <h2>Redis</h2>
 * One ZSET per agent, {@code user:support:rl:{<agent>}}, scored by Redis
 * {@code TIME} in milliseconds (the replicas' clocks never enter the window).
 * One Lua script evicts entries older than a day, counts the short window and
 * the day, and admits (adds an entry) only when both are under their limit — so
 * two replicas racing for the last slot cannot both get it. The key ALWAYS
 * carries a TTL of one day, refreshed on every admit: an agent who stops
 * working leaves nothing behind. A refused call is not added: a 429 costs the
 * agent nothing further, and the window drains on its own.
 *
 * <h2>Redis down: never fail open</h2>
 * If Redis throws or answers nonsense, a per-replica in-memory window with the
 * same rules decides instead, and {@code user.support.limiter.degraded} counts
 * it. While degraded, the effective limit is the configured one multiplied by
 * the number of replicas (each keeps its own window) — documented and alerted,
 * and far better than no limit. The memory window only knows the calls it saw
 * while degraded; when Redis answers again it is ignored and ages out.
 */
@Component
@Slf4j
public class SupportLookupLimiter {

    static final String KEY_PREFIX = "user:support:rl:";

    /**
     * KEYS[1] the agent's ZSET. ARGV: short window ms, short max, day ms, day
     * max, a random member suffix. Returns {0,0} admitted, {1, retryMs} over the
     * short window, {2, retryMs} over the day.
     */
    static final String SCRIPT = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local shortMs = tonumber(ARGV[1])
            local dayMs = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - dayMs)
            local shortStart = now - shortMs
            local inShort = redis.call('ZCOUNT', KEYS[1], '(' .. shortStart, '+inf')
            if inShort >= tonumber(ARGV[2]) then
              local oldest = redis.call('ZRANGEBYSCORE', KEYS[1], '(' .. shortStart, '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
              if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], dayMs) end
              return {1, tonumber(oldest[2]) + shortMs - now}
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
              local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], dayMs) end
              return {2, tonumber(oldest[2]) + dayMs - now}
            end
            redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[5])
            redis.call('PEXPIRE', KEYS[1], dayMs)
            return {0, 0}
            """;

    static final Duration DAY = Duration.ofDays(1);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> REDIS_SCRIPT = new DefaultRedisScript<>(SCRIPT, List.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SupportProperties.Limiter config;
    private final SupportMetrics metrics;
    private final LongSupplier clockMillis;

    /** Per-replica fallback windows, only ever written while Redis cannot answer. Bounded and self-expiring. */
    private final Cache<String, Deque<Long>> fallback = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(DAY)
            .build();

    @org.springframework.beans.factory.annotation.Autowired
    public SupportLookupLimiter(ObjectProvider<StringRedisTemplate> redis, SupportProperties properties,
                                SupportMetrics metrics) {
        this(redis.getIfAvailable(), properties.getLimiter(), metrics, System::currentTimeMillis);
    }

    SupportLookupLimiter(StringRedisTemplate redis, SupportProperties.Limiter config, SupportMetrics metrics,
                         LongSupplier clockMillis) {
        this.redis = redis;
        this.config = config;
        this.metrics = metrics;
        this.clockMillis = clockMillis;
    }

    /**
     * Counts one call for {@code agent}, or refuses it.
     *
     * @throws SupportPolicyException 429 {@code lookup_rate_limited} over either window
     */
    public void acquire(SupportAgent agent) {
        String key = KEY_PREFIX + "{" + agent.limiterKey() + "}";
        long[] verdict = redis == null ? null : viaRedis(key);
        if (verdict == null) {
            metrics.limiterDegraded("acquire");
            verdict = inMemory(key);
        }
        if (verdict[0] == 0) return;
        String window = verdict[0] == 1 ? "10m" : "day";
        long retrySeconds = Math.max(1, (verdict[1] + 999) / 1000);
        metrics.limited(window);
        metrics.lookup(SupportPolicyException.LOOKUP_RATE_LIMITED);
        if ("day".equals(window)) {
            log.warn("Support agent reached the DAILY lookup cap agent={} retryAfterSeconds={}",
                    agent.userUuid(), retrySeconds);
        } else {
            log.info("Support agent hit the short-window lookup limit agent={} retryAfterSeconds={}",
                    agent.userUuid(), retrySeconds);
        }
        throw SupportPolicyException.rateLimited(retrySeconds, window);
    }

    /** The verdict from Redis, or null when Redis could not give a sane one. */
    private long[] viaRedis(String key) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> out = (List<Object>) redis.execute(REDIS_SCRIPT, List.of(key),
                    String.valueOf(config.getShortWindow().toMillis()), String.valueOf(config.getShortWindowMax()),
                    String.valueOf(DAY.toMillis()), String.valueOf(config.getDailyMax()),
                    Long.toHexString(RANDOM.nextLong()));
            if (out == null || out.size() != 2) {
                log.error("Support lookup limiter: Redis returned an unexpected reply; using the in-memory window");
                return null;
            }
            long code = ((Number) out.get(0)).longValue();
            long retryMs = ((Number) out.get(1)).longValue();
            if (code < 0 || code > 2) {
                log.error("Support lookup limiter: Redis returned an unknown verdict; using the in-memory window");
                return null;
            }
            return new long[] {code, Math.max(0, retryMs)};
        } catch (RuntimeException e) {
            log.error("Support lookup limiter: Redis unreachable ({}); using the per-replica in-memory window",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The same rules as the script, per replica. Every read and write of one
     * agent's window happens inside that key's {@code compute}, so concurrent
     * calls on this replica cannot both take the last slot.
     */
    long[] inMemory(String key) {
        long now = clockMillis.getAsLong();
        long shortMs = config.getShortWindow().toMillis();
        long dayMs = DAY.toMillis();
        AtomicReference<long[]> verdict = new AtomicReference<>();
        fallback.asMap().compute(key, (k, window) -> {
            Deque<Long> w = window == null ? new ArrayDeque<>() : window;
            while (!w.isEmpty() && w.peekFirst() <= now - dayMs) w.pollFirst();
            long shortStart = now - shortMs;
            long inShort = 0;
            // Both maxima are at least 1 (SupportProperties refuses less), so a
            // refusal below always has an entry to date it from: the first in the
            // short window, or the head of a non-empty day window.
            long oldestShort = now;
            for (Iterator<Long> it = w.iterator(); it.hasNext(); ) {
                long at = it.next();
                if (at > shortStart) {
                    if (inShort == 0) oldestShort = at;
                    inShort++;
                }
            }
            if (inShort >= config.getShortWindowMax()) {
                verdict.set(new long[] {1, oldestShort + shortMs - now});
            } else if (w.size() >= config.getDailyMax()) {
                verdict.set(new long[] {2, w.getFirst() + dayMs - now});
            } else {
                w.addLast(now);
                verdict.set(new long[] {0, 0});
            }
            return w;
        });
        return verdict.get();
    }
}
