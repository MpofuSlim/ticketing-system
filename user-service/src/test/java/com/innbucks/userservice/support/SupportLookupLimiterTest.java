package com.innbucks.userservice.support;

import com.innbucks.userservice.exception.SupportPolicyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-agent lookup limit (design §3.1.3, D11): a sliding window, and never
 * fail-open. The Lua script itself runs against a real Redis in
 * {@code SupportLookupLimiterRedisIT}; this pins the verdict mapping, the
 * in-memory window's rules and the Redis-down fallback.
 */
class SupportLookupLimiterTest {

    private static SupportAgent agent() {
        return new SupportAgent("agent@innbucks.co.zw", 7L, UUID.randomUUID(), "agent@innbucks.co.zw", null,
                null, Set.of());
    }

    private static SupportProperties.Limiter limits(int shortMax, int dailyMax) {
        SupportProperties.Limiter l = new SupportProperties.Limiter();
        l.setShortWindow(Duration.ofMinutes(10));
        l.setShortWindowMax(shortMax);
        l.setDailyMax(dailyMax);
        return l;
    }

    @Test
    @DisplayName("Redis down: the in-memory window decides — never fail open — and the degradation is counted")
    void redisDown_fallsBackToMemory_neverOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong now = new AtomicLong(1_000_000L);
        SupportLookupLimiter limiter = new SupportLookupLimiter(redis, limits(3, 100), new SupportMetrics(registry),
                now::get);
        SupportAgent a = agent();

        limiter.acquire(a);
        limiter.acquire(a);
        limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a))
                .isInstanceOf(SupportPolicyException.class)
                .satisfies(e -> {
                    SupportPolicyException s = (SupportPolicyException) e;
                    assertThat(s.getErrorCode()).isEqualTo("lookup_rate_limited");
                    assertThat(s.getStatus().value()).isEqualTo(429);
                    assertThat(s.getRetryAfterSeconds()).isEqualTo(600L);
                    assertThat(s.getExtra()).containsEntry("window", "10m").containsEntry("retryAfterSeconds", 600L);
                });
        assertThat(registry.counter("user.support.limiter.degraded", "op", "acquire").count()).isEqualTo(4.0);
        assertThat(registry.counter("user.support.limited", "window", "10m").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no Redis at all is the same as Redis down")
    void noRedisTemplate_usesMemory() {
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(1, 100), SupportMetrics.none(),
                () -> 5_000L);
        SupportAgent a = agent();
        limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(SupportPolicyException.class);
    }

    @Test
    @DisplayName("the window SLIDES: the oldest call ageing out frees exactly one slot")
    void slidingWindow() {
        AtomicLong now = new AtomicLong(0L);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(2, 100), SupportMetrics.none(), now::get);
        SupportAgent a = agent();
        limiter.acquire(a);                        // t=0
        now.set(Duration.ofMinutes(5).toMillis());
        limiter.acquire(a);                        // t=5m
        assertThatThrownBy(() -> limiter.acquire(a))
                .satisfies(e -> assertThat(((SupportPolicyException) e).getRetryAfterSeconds()).isEqualTo(300L));
        now.set(Duration.ofMinutes(10).toMillis() + 1);
        limiter.acquire(a);                        // the t=0 call has left the window
        assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(SupportPolicyException.class);
    }

    @Test
    @DisplayName("the DAILY cap: counted across short windows, retry when the day's oldest call ages out")
    void dailyCap() {
        AtomicLong now = new AtomicLong(0L);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(60, 3), SupportMetrics.none(), now::get);
        SupportAgent a = agent();
        for (int i = 0; i < 3; i++) {
            now.set(Duration.ofHours(i).toMillis());
            limiter.acquire(a);
        }
        now.set(Duration.ofHours(5).toMillis());
        assertThatThrownBy(() -> limiter.acquire(a))
                .satisfies(e -> {
                    SupportPolicyException s = (SupportPolicyException) e;
                    assertThat(s.getExtra()).containsEntry("window", "day");
                    assertThat(s.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(19).toSeconds());
                    assertThat(s.getMessage()).startsWith("You've reached today's limit");
                });
    }

    @Test
    @DisplayName("a refused call is not counted: the agent's window drains on its own")
    void refusalsAreNotCounted() {
        AtomicLong now = new AtomicLong(0L);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(1, 100), SupportMetrics.none(), now::get);
        SupportAgent a = agent();
        limiter.acquire(a);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(SupportPolicyException.class);
        }
        now.set(Duration.ofMinutes(10).toMillis() + 1);
        limiter.acquire(a);
    }

    @Test
    @DisplayName("agents are counted apart")
    void perAgent() {
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(1, 100), SupportMetrics.none(),
                () -> 0L);
        limiter.acquire(agent());
        limiter.acquire(agent());
    }

    @Test
    @DisplayName("Redis's verdicts map to admit / 10m / day, and a nonsense reply falls back rather than admitting")
    void redisVerdicts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SupportLookupLimiter limiter = new SupportLookupLimiter(redis, limits(1, 1), SupportMetrics.none(),
                () -> 0L);
        SupportAgent a = agent();

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 0L));
        limiter.acquire(a);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 61_500L));
        assertThatThrownBy(() -> limiter.acquire(a)).satisfies(e -> {
            SupportPolicyException s = (SupportPolicyException) e;
            assertThat(s.getExtra()).containsEntry("window", "10m");
            assertThat(s.getRetryAfterSeconds()).isEqualTo(62L);
        });

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(2L, 3_600_000L));
        assertThatThrownBy(() -> limiter.acquire(a)).satisfies(e ->
                assertThat(((SupportPolicyException) e).getExtra()).containsEntry("window", "day"));

        // Nonsense from Redis: the memory window decides (limit 1 → the first is admitted, the second refused).
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(9L));
        limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(SupportPolicyException.class);
    }

    @Test
    @DisplayName("the in-memory window is race-free on one replica: N threads, exactly the limit admitted")
    void memoryWindowIsRaceFree() throws Exception {
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, limits(10, 100), SupportMetrics.none(),
                System::currentTimeMillis);
        SupportAgent a = agent();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    limiter.acquire(a);
                    admitted.incrementAndGet();
                } catch (SupportPolicyException ignored) {
                    // refused
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(admitted.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("the Redis key names the agent by uuid inside a hash tag, never by email")
    void keyShape() {
        UUID uuid = UUID.randomUUID();
        SupportAgent a = new SupportAgent("agent@innbucks.co.zw", 7L, uuid, "agent@innbucks.co.zw", null, null, Set.of());
        assertThat(SupportLookupLimiter.KEY_PREFIX + "{" + a.limiterKey() + "}")
                .isEqualTo("user:support:rl:{" + uuid + "}");
        SupportAgent unresolved = new SupportAgent("someone@innbucks.co.zw", null, null, null, null, null, Set.of());
        assertThat(unresolved.limiterKey()).startsWith("sub-").doesNotContain("someone");
        // The script always leaves a TTL behind.
        assertThat(SupportLookupLimiter.SCRIPT).contains("PEXPIRE");
    }
}
