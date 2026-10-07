package com.innbucks.userservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LoginRateLimiterTest {

    private static final int LOGIN_ID_MAX = 5;
    private static final int LOGIN_IP_MAX = 20;
    private static final int REFRESH_ID_MAX = 10;
    private static final int REFRESH_IP_MAX = 60;
    private static final int WINDOW_SECONDS = 60;

    private StringRedisTemplate redis;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        limiter = new LoginRateLimiter(
                LOGIN_ID_MAX, LOGIN_IP_MAX, WINDOW_SECONDS,
                REFRESH_ID_MAX, REFRESH_IP_MAX, WINDOW_SECONDS,
                redis);
    }

    @Test
    void checkLogin_allows_whenCountStaysUnderCap() {
        when(incrementOf(k -> true)).thenReturn(1L, 2L, 3L);

        // 3 attempts on the same identifier — well under the max of 5.
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkLogin_throws429_whenIdentifierCounterExceedsCap() {
        // Identifier bucket hits perIdentifierMax + 1. Per-IP returns
        // safe values — only identifier should trip.
        when(incrementOf(k -> k.contains(":id:"))).thenReturn(6L);
        when(incrementOf(k -> k.contains(":ip:"))).thenReturn(2L);

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
        when(incrementOf(k -> k.contains(":id:"))).thenReturn(1L);
        when(incrementOf(k -> k.contains(":ip:"))).thenReturn(21L);

        LoginRateLimiter.RateLimitedException ex = assertThrows(
                LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkLogin("victim-N@example.com", "1.2.3.4"));

        assertTrue(ex.getMessage().toLowerCase().contains("address"),
                "ip-dimension message must mention 'address', not 'account': " + ex.getMessage());
    }

    @Test
    void checkLogin_incrementsAndSetsTheWindow_inOneScript_perBucket() {
        // One atomic script per bucket carries the window, so a counter can
        // never exist without its TTL (a TTL-less counter is a permanent
        // lockout, and under noeviction it never leaves Redis either).
        when(incrementOf(k -> true)).thenReturn(1L);

        limiter.checkLogin("alice@example.com", "1.2.3.4");

        String windowMs = Long.toString(WINDOW_SECONDS * 1000L);
        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW),
                eq(List.of("auth:rl:login:id:alice@example.com")), eq(windowMs));
        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW),
                eq(List.of("auth:rl:login:ip:1.2.3.4")), eq(windowMs));
        verify(redis, never()).expire(anyString(), any(java.time.Duration.class));
        verify(redis, never()).opsForValue();
    }

    @Test
    void theScript_setsTheTtlOnlyWhenTheKeyHasNone() {
        // PTTL < 0, not "count == 1": a steady stream never extends the window
        // (the TTL is only set when absent), and a counter the old two-step
        // INCR/EXPIRE left without a TTL is healed on its next hit.
        String lua = LoginRateLimiter.INCREMENT_IN_WINDOW_LUA;
        assertTrue(lua.contains("redis.call('INCR', KEYS[1])"), lua);
        assertTrue(lua.contains("redis.call('PTTL', KEYS[1]) < 0"), lua);
        assertTrue(lua.contains("redis.call('PEXPIRE', KEYS[1], ARGV[1])"), lua);
        assertEquals(Long.class, LoginRateLimiter.INCREMENT_IN_WINDOW.getResultType());
    }

    @Test
    void checkLogin_fallsBackInMemory_whenRedisRefusesTheWrite() {
        // noeviction at maxmemory answers every write with -OOM, which reaches
        // us as a RuntimeException from the script — the same fallback as an
        // outage, never fail-open.
        when(incrementOf(k -> true)).thenThrow(
                new org.springframework.dao.InvalidDataAccessApiUsageException(
                        "OOM command not allowed when used memory > 'maxmemory'."));

        for (int i = 0; i < LOGIN_ID_MAX; i++) {
            assertDoesNotThrow(() -> limiter.checkLogin("oom@example.com", "8.8.8.8"));
        }
        assertThrows(LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkLogin("oom@example.com", "8.8.8.8"));
    }

    @Test
    void checkLogin_normalisesIdentifier_caseAndWhitespace() {
        // "Alice@x.com  " and "alice@x.com" must hit the same bucket;
        // otherwise an attacker can vary case to multiply the budget.
        when(incrementOf(k -> true)).thenReturn(1L);

        limiter.checkLogin("  Alice@Example.com ", "1.2.3.4");
        limiter.checkLogin("alice@example.com", "1.2.3.4");

        // Both calls must increment the SAME identifier-bucket key.
        verify(redis, times(2)).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:login:id:alice@example.com"), any());
    }

    @Test
    void checkLogin_skipsIdentifierBucket_whenIdentifierMissing() {
        // FE sends an empty/missing identifier (validation will reject
        // it downstream). We should still throttle the per-IP bucket so
        // a spammer doesn't get unlimited free attempts.
        when(incrementOf(k -> true)).thenReturn(1L);

        limiter.checkLogin(null, "1.2.3.4");
        limiter.checkLogin("   ", "1.2.3.4");

        // Only the IP bucket should be touched.
        verify(redis, times(2)).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:login:ip:1.2.3.4"), any());
        verify(redis, never()).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyedContaining(":id:"), any());
    }

    @Test
    void checkLogin_allowsHonestUserUnderCap_whenRedisUnreachable() {
        // Redis down: the limiter falls back to a per-instance in-memory counter
        // (not fail-open). A single honest attempt is well under the cap, so it
        // must still pass — a brief Redis hiccup can't lock honest users out.
        when(incrementOf(k -> true)).thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> limiter.checkLogin("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkLogin_inMemoryFallbackThrottles_whenRedisUnreachable() {
        // With Redis down the limiter must NOT fail open: the in-memory fallback
        // enforces the same identifier cap (5), so the first five attempts from
        // one identifier pass and the sixth is rejected.
        when(incrementOf(k -> true)).thenThrow(new RedisConnectionFailureException("down"));

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
        when(incrementOf(k -> true)).thenReturn(8L);

        assertDoesNotThrow(() -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkRefresh_throws_atCapPlusOne() {
        // Boundary: cap is 10. Tenth attempt (count=10) passes; 11th trips.
        when(incrementOf(k -> k.contains(":id:"))).thenReturn(10L);
        assertDoesNotThrow(() -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));

        when(incrementOf(k -> k.contains(":id:"))).thenReturn(11L);
        assertThrows(LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkRefresh("alice@example.com", "1.2.3.4"));
    }

    @Test
    void checkRefresh_keysAreSegregatedFromLogin() {
        // The two endpoints' counters MUST live under distinct prefixes
        // so a flood of refresh attempts doesn't burn the login budget
        // (and vice versa).
        when(incrementOf(k -> true)).thenReturn(1L);

        limiter.checkLogin("alice@example.com", "1.2.3.4");
        limiter.checkRefresh("alice@example.com", "1.2.3.4");

        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:login:id:alice@example.com"), any());
        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:refresh:id:alice@example.com"), any());
        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:login:ip:1.2.3.4"), any());
        verify(redis).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyed("auth:rl:refresh:ip:1.2.3.4"), any());
    }

    @Test
    void checkRefresh_acceptsNullSubject_andStillThrottlesByIp() {
        // Malformed refresh tokens return null subject. The host
        // spraying them must still get throttled by IP.
        when(incrementOf(k -> k.contains(":ip:"))).thenReturn(61L);
        when(incrementOf(k -> k.contains(":id:"))).thenReturn(1L);

        LoginRateLimiter.RateLimitedException ex = assertThrows(
                LoginRateLimiter.RateLimitedException.class,
                () -> limiter.checkRefresh(null, "1.2.3.4"));
        assertTrue(ex.getMessage().toLowerCase().contains("address"));
        verify(redis, never()).execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW), keyedContaining(":id:"), any());
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

    /** The limiter's one Redis call for the bucket whose key matches. */
    private Long incrementOf(Predicate<String> key) {
        return redis.execute(eq(LoginRateLimiter.INCREMENT_IN_WINDOW),
                argThat((List<String> keys) -> keys != null && !keys.isEmpty() && key.test(keys.get(0))),
                any());
    }

    private static List<String> keyed(String exact) {
        return argThat(keys -> keys != null && keys.equals(List.of(exact)));
    }

    private static List<String> keyedContaining(String fragment) {
        return argThat(keys -> keys != null && !keys.isEmpty() && keys.get(0).contains(fragment));
    }
}
