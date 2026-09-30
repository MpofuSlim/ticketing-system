package com.innbucks.userservice.support;

import com.innbucks.userservice.exception.SupportPolicyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limiter's Lua script against a REAL Redis — the part a mocked template
 * cannot execute. Pure JUnit + Testcontainers, no Spring context; skipped
 * without Docker, run by CI's {@code mvn verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
class SupportLookupLimiterRedisIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) factory.destroy();
    }

    private static SupportLookupLimiter limiter(int shortMax, int dailyMax, SimpleMeterRegistry registry) {
        SupportProperties.Limiter l = new SupportProperties.Limiter();
        l.setShortWindow(Duration.ofMinutes(10));
        l.setShortWindowMax(shortMax);
        l.setDailyMax(dailyMax);
        return new SupportLookupLimiter(redis, l, new SupportMetrics(registry), System::currentTimeMillis);
    }

    private static SupportAgent agent() {
        return new SupportAgent("agent@innbucks.co.zw", 7L, UUID.randomUUID(), "agent@innbucks.co.zw", null, null,
                Set.of());
    }

    @Test
    @DisplayName("the script admits up to the short limit, then refuses with a Retry-After inside the window")
    void shortWindow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SupportLookupLimiter limiter = limiter(3, 100, registry);
        SupportAgent a = agent();
        for (int i = 0; i < 3; i++) limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).satisfies(e -> {
            SupportPolicyException s = (SupportPolicyException) e;
            assertThat(s.getErrorCode()).isEqualTo("lookup_rate_limited");
            assertThat(s.getExtra()).containsEntry("window", "10m");
            assertThat(s.getRetryAfterSeconds()).isBetween(595L, 600L);
        });
        // Redis answered every time: no degradation.
        assertThat(registry.find("user.support.limiter.degraded").counter()).isNull();
    }

    @Test
    @DisplayName("the day cap is enforced by the same script")
    void dailyCap() {
        SupportLookupLimiter limiter = limiter(100, 2, new SimpleMeterRegistry());
        SupportAgent a = agent();
        limiter.acquire(a);
        limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).satisfies(e ->
                assertThat(((SupportPolicyException) e).getExtra()).containsEntry("window", "day"));
    }

    @Test
    @DisplayName("the key ALWAYS carries a TTL of at most a day, and holds only admitted calls")
    void keyHasATtl() {
        SupportLookupLimiter limiter = limiter(2, 100, new SimpleMeterRegistry());
        SupportAgent a = agent();
        limiter.acquire(a);
        limiter.acquire(a);
        assertThatThrownBy(() -> limiter.acquire(a)).isInstanceOf(SupportPolicyException.class);
        String key = SupportLookupLimiter.KEY_PREFIX + "{" + a.limiterKey() + "}";
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(Duration.ofDays(1).toMillis());
        assertThat(redis.opsForZSet().zCard(key)).isEqualTo(2L);
    }

    @Test
    @DisplayName("two replicas racing for the last slots cannot both get one: exactly the limit is admitted")
    void concurrentAdmitsNeverExceedTheLimit() throws Exception {
        SupportLookupLimiter replicaA = limiter(10, 100, new SimpleMeterRegistry());
        SupportLookupLimiter replicaB = limiter(10, 100, new SimpleMeterRegistry());
        SupportAgent a = agent();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            SupportLookupLimiter replica = i % 2 == 0 ? replicaA : replicaB;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    replica.acquire(a);
                    admitted.incrementAndGet();
                } catch (SupportPolicyException ignored) {
                    // refused
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(admitted.get()).isEqualTo(10);
    }
}
