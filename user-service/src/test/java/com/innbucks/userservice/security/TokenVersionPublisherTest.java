package com.innbucks.userservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link TokenVersionPublisher} — the A07 / CWE-613 shared-Redis
 * publish side. Pure Mockito, no live Redis, no {@code @SpringBootTest}. Pins the
 * exact wire contract downstream services read:
 * {@code auth:tokenver:<userUuid> -> "<version>"}.
 */
class TokenVersionPublisherTest {

    /** 1 day, in millis — the value the test/it profiles pin jwt.refresh-expiration to. */
    private static final long REFRESH_TTL_MS = 86_400_000L;

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private TokenVersionPublisher publisher;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        publisher = new TokenVersionPublisher(redis);
        // Field-injected @Value in production; set it directly here.
        ReflectionTestUtils.setField(publisher, "refreshExpirationMs", REFRESH_TTL_MS);
    }

    @Test
    void publish_writesCanonicalKeyValue_withRefreshLifetimeTtl() {
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 8L);

        // Key = prefix + canonical hyphenated-lowercase UUID (== the JWT userUuid
        // claim); value = the version as a decimal String; TTL = refresh lifetime.
        verify(ops).set(
                eq(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + uuid.toString()),
                eq("8"),
                eq(Duration.ofMillis(REFRESH_TTL_MS)));
    }

    @Test
    void publish_isNoOp_whenUuidIsNull() {
        // Legacy tokens carry no userUuid claim -> nothing downstream can key on,
        // so we must not write anything (downstream fails open for them).
        publisher.publish(null, 5L);

        verify(ops, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void publish_failsOpen_whenRedisThrows() {
        // A Redis outage must NOT propagate — Postgres (users.token_version) is the
        // source of truth for user-service's own JwtFilter; downstream keeps the
        // access-token TTL backstop. (ValueOperations#set is void -> doThrow form.)
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(any(), any(), any(Duration.class));

        assertDoesNotThrow(() -> publisher.publish(UUID.randomUUID(), 3L));
    }

    @Test
    void publish_usesFallbackTtl_whenRefreshLifetimeMisconfiguredNonPositive() {
        // Guard: a 0/negative refresh lifetime must not lead to a SET with a
        // non-positive expiry (which Redis rejects) — fall back to 30 days.
        ReflectionTestUtils.setField(publisher, "refreshExpirationMs", 0L);
        UUID uuid = UUID.randomUUID();

        publisher.publish(uuid, 1L);

        verify(ops).set(
                eq(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + uuid.toString()),
                eq("1"),
                eq(Duration.ofDays(30)));
    }
}
