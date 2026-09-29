package com.innbucks.userservice.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenVersionPublisher}'s never-lower script against a REAL Redis — the
 * Lua is the part a mocked template cannot execute. Pure JUnit + Testcontainers,
 * no Spring context; skipped without Docker, run by CI's {@code mvn verify}.
 *
 * <p>The case that matters: a login commits v+1, a deactivation commits v+2, and
 * the login thread's after-commit publish lands LAST. A plain {@code SET} left
 * v+1 published, so every downstream service accepted the login's access token
 * for a deactivated account until it expired.
 */
@Testcontainers(disabledWithoutDocker = true)
class TokenVersionPublisherRedisIT {

    private static final long TTL_MS = 86_400_000L;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private TokenVersionPublisher publisher;

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
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        publisher = new TokenVersionPublisher(redis);
        ReflectionTestUtils.setField(publisher, "refreshExpirationMs", TTL_MS);
    }

    private static String key(UUID uuid) {
        return TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + uuid;
    }

    @Test
    void twoPublishesLandingInReverseOrder_leaveTheNewerVersion() {
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 12L); // the deactivation's v+2 lands first…
        publisher.publish(uuid, 11L); // …the login's v+1 arrives late

        assertThat(redis.opsForValue().get(key(uuid))).isEqualTo("12");
    }

    @Test
    void inOrderPublishesAdvance() {
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 11L);
        publisher.publish(uuid, 12L);

        assertThat(redis.opsForValue().get(key(uuid))).isEqualTo("12");
    }

    @Test
    void theFirstPublish_writesTheValueWithTheRefreshLifetimeTtl() {
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 1L);

        assertThat(redis.opsForValue().get(key(uuid))).isEqualTo("1");
        Long ttl = redis.getExpire(key(uuid), TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(TTL_MS);
    }

    @Test
    void aStaleVersion_doesNotRefreshTheNewerOnesTtl() {
        UUID uuid = UUID.randomUUID();
        redis.opsForValue().set(key(uuid), "12", java.time.Duration.ofSeconds(60));

        publisher.publish(uuid, 11L);

        assertThat(redis.getExpire(key(uuid), TimeUnit.SECONDS)).isLessThanOrEqualTo(60L);
    }

    @Test
    void anEqualVersion_isRewritten_whichOnlyRefreshesTheTtl() {
        UUID uuid = UUID.randomUUID();
        redis.opsForValue().set(key(uuid), "7", java.time.Duration.ofSeconds(60));

        publisher.publish(uuid, 7L);

        assertThat(redis.opsForValue().get(key(uuid))).isEqualTo("7");
        assertThat(redis.getExpire(key(uuid), TimeUnit.SECONDS)).isGreaterThan(60L);
    }

    @Test
    void aNonNumericStoredValue_isReplaced() {
        UUID uuid = UUID.randomUUID();
        redis.opsForValue().set(key(uuid), "garbage");

        publisher.publish(uuid, 3L);

        assertThat(redis.opsForValue().get(key(uuid))).isEqualTo("3");
    }

    @Test
    void theBulkPublish_pipelinesTheSameNeverLowerScript_acrossBatches() {
        // A role losing a PLATFORM code publishes every holder at once, in
        // pipelined batches. Same script: a key already holding a NEWER version
        // (a concurrent login published after the bulk bump read its value) is
        // left alone, every other key is written with the refresh-lifetime TTL.
        java.util.Map<UUID, Long> versions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < TokenVersionPublisher.PIPELINE_BATCH + 3; i++) {
            versions.put(UUID.randomUUID(), 4L);
        }
        UUID newer = versions.keySet().iterator().next();
        redis.opsForValue().set(key(newer), "9");

        publisher.publishAll(versions);

        assertThat(redis.opsForValue().get(key(newer))).isEqualTo("9");
        versions.keySet().stream().filter(u -> !u.equals(newer)).forEach(u -> {
            assertThat(redis.opsForValue().get(key(u))).isEqualTo("4");
        });
        UUID last = new java.util.ArrayList<>(versions.keySet()).get(versions.size() - 1);
        assertThat(redis.getExpire(key(last), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(TTL_MS);
    }
}
