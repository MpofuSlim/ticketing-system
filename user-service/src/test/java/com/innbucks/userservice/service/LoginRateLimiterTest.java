package com.innbucks.userservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LoginRateLimiterTest {

    private static final int LOGIN_ID_MAX = 5;
    private static final int LOGIN_IP_MAX = 20;
    private static final int REFRESH_ID_MAX = 10;
    private static final int REFRESH_IP_MAX = 60;
    private static final int WINDOW_SECONDS = 60;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> ops;
    private StringRedisTemplate redis;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        ops = mock(ValueOperations.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        limiter = new LoginRateLimiter(
                LOGIN_ID_MAX, LOGIN_IP_MAX, WINDOW_SECONDS,
                REFRESH_ID_MAX, REFRESH_IP_MAX, WINDOW_SECONDS,
                redis);
    }

    @Test
    void checkLogin_allows_whenCountStaysUnderCap() {
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);

        // 3 attempts on the same identifier — well under the max of 5.
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkLogin_throws429_whenIdentifierCounterExceedsCap() {
        // Identifier bucket hits perIdentifierMax + 1. Per-IP returns
        // safe values — only identifier should trip.
        when(ops.increment(contains(":id:"))).thenReturn(6L);
        when(ops.increment(contains(":ip:"))).thenReturn(2L);

        LoginRateLimiter.RateLimitedException ex = assertThrows(
                LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkLogin("alice@example.com", "1.2.3.4"));

        assertTrue(ex.getMessage().toLowerCase().contains("account"),
                "identifier-dimension message must mention 'account', not 'address': " + ex.getMessage());
        assertEquals(WINDOW_SECONDS, ex.getRetryAfterSeconds());
    }

    @Test
    void checkLogin_throws429_whenIpCounterExceedsCap() {
        // The single shared host is spraying many accounts. Identifier
        // bucket low (different victim each call), but the IP bucket
        // hits perIpMax + 1.
        when(ops.increment(contains(":id:"))).thenReturn(1L);
        when(ops.increment(contains(":ip:"))).thenReturn(21L);

        LoginRateLimiter.RateLimitedException ex = assertThrows(
                LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkLogin("victim-N@example.com", "1.2.3.4"));

        assertTrue(ex.getMessage().toLowerCase().contains("address"),
                "ip-dimension message must mention 'address', not 'account': " + ex.getMessage());
    }

    @Test
    void checkLogin_setsTtl_onlyOnFirstIncrement() {
        // INCR returning 1L means "this is the first attempt in the
        // window" — limiter must set the TTL so the window starts
        // counting from this attempt, not be reset by every later one.
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.checkLogin("alice@example.com", "1.2.3.4");

        // expire() called twice: once for the identifier bucket, once
        // for the IP bucket. Subsequent calls (where INCR returns >1)
        // must NOT extend the TTL.
        verify(redis, times(2)).expire(anyString(), eq(Duration.ofSeconds(WINDOW_SECONDS)));
    }

    @Test
    void checkLogin_doesNotSetTtl_onSubsequentIncrements() {
        // Counter is already at 3 — INCR returns 4, not 1. The TTL was
        // set on the first hit; extending it now would let a steady
        // 1-per-second stream rate-limit-evade by never letting the
        // window roll.
        when(ops.increment(anyString())).thenReturn(4L);

        limiter.checkLogin("alice@example.com", "1.2.3.4");

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void checkLogin_normalisesIdentifier_caseAndWhitespace() {
        // "Alice@x.com  " and "alice@x.com" must hit the same bucket;
        // otherwise an attacker can vary case to multiply the budget.
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.checkLogin("  Alice@Example.com ", "1.2.3.4");
        limiter.checkLogin("alice@example.com", "1.2.3.4");

        // Both calls must increment the SAME identifier-bucket key.
        verify(ops, times(2)).increment("auth:rl:login:id:alice@example.com");
    }

    @Test
    void checkLogin_skipsIdentifierBucket_whenIdentifierMissing() {
        // FE sends an empty/missing identifier (validation will reject
        // it downstream). We should still throttle the per-IP bucket so
        // a spammer doesn't get unlimited free attempts.
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.checkLogin(null, "1.2.3.4");
        limiter.checkLogin("   ", "1.2.3.4");

        // Only the IP bucket should be touched.
        verify(ops, times(2)).increment("auth:rl:login:ip:1.2.3.4");
        verify(ops, never()).increment(contains(":id:"));
    }

    @Test
    void checkLogin_allowsHonestUserUnderCap_whenRedisUnreachable() {
        // Redis down: the limiter falls back to a per-instance in-memory counter
        // (not fail-open). A single honest attempt is well under the cap, so it
        // must still pass — a brief Redis hiccup can't lock honest users out.
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkLogin_inMemoryFallbackThrottles_whenRedisUnreachable() {
        // With Redis down the limiter must NOT fail open: the in-memory fallback
        // enforces the same identifier cap (5), so the first five attempts from
        // one identifier pass and the sixth is rejected.
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        for (int i = 0; i < LOGIN_ID_MAX; i++) {
            int attempt = i + 1;
            assertDoesNotThrow(() -> limiter.checkLogin("attacker@example.com", "9.9.9.9"),
                    "attempt " + attempt + " should pass under the cap");
        }
        assertThrows(LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkLogin("attacker@example.com", "9.9.9.9"));
    }

    @Test
    void checkRefresh_usesHigherCap_thanLogin() {
        // perIdentifierMax for refresh is 10. Hitting 8 should still
        // pass, even though that would have tripped the login (5).
        when(ops.increment(anyString())).thenReturn(8L);

        assertDoesNotThrow(() -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkRefresh_throws_atCapPlusOne() {
        // Boundary: cap is 10. Tenth attempt (count=10) passes; 11th trips.
        when(ops.increment(contains(":id:"))).thenReturn(10L);
        assertDoesNotThrow(() -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));

        when(ops.increment(contains(":id:"))).thenReturn(11L);
        assertThrows(LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkRefresh_keysAreSegregatedFromLogin() {
        // The two endpoints' counters MUST live under distinct prefixes
        // so a flood of refresh attempts doesn't burn the login budget
        // (and vice versa).
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.checkLogin("alice@example.com", "1.2.3.4");
        limiter.checkRefresh("alice@example.com", "1.2.3.4");

        verify(ops).increment("auth:rl:login:id:alice@example.com");
        verify(ops).increment("auth:rl:refresh:id:alice@example.com");
        verify(ops).increment("auth:rl:login:ip:1.2.3.4");
        verify(ops).increment("auth:rl:refresh:ip:1.2.3.4");
    }

    @Test
    void checkRefresh_acceptsNullSubject_andStillThrottlesByIp() {
        // Malformed refresh tokens return null subject. The host
        // spraying them must still get throttled by IP.
        when(ops.increment(contains(":ip:"))).thenReturn(61L);
        when(ops.increment(contains(":id:"))).thenReturn(1L);

        LoginRateLimiter.RateLimitedException ex = assertThrows(
                LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkRefresh(null, "1.2.3.4"));
        assertTrue(ex.getMessage().toLowerCase().contains("address"));
        verify(ops, never()).increment(contains(":id:"));
    }

    @Test
    void constructor_rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoginRateLimiter(0, LOGIN_IP_MAX, WINDOW_SECONDS,
                        REFRESH_ID_MAX, REFRESH_IP_MAX, WINDOW_SECONDS, redis));
        assertThrows(IllegalArgumentException.class,
                () -> new LoginRateLimiter(LOGIN_ID_MAX, -1, WINDOW_SECONDS,
                        REFRESH_ID_MAX, REFRESH_IP_MAX, WINDOW_SECONDS, redis));
        assertThrows(IllegalArgumentException.class,
                () -> new LoginRateLimiter(LOGIN_ID_MAX, LOGIN_IP_MAX, 0,
                        REFRESH_ID_MAX, REFRESH_IP_MAX, WINDOW_SECONDS, redis));
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.contains(fragment));
    }
}
