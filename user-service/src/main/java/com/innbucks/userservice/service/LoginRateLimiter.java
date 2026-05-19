package com.innbucks.userservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Brute-force / credential-stuffing gate on the public auth endpoints
 * ({@code /auth/login}, {@code /auth/refresh}).
 *
 * <p>The api-gateway's {@code RequestRateLimiter} keys by JWT subject, so
 * it can't see /auth/** traffic (no token yet at login time, and refresh
 * presents a token that the gateway doesn't validate). Without an
 * application-level limiter, an attacker could fire millions of password
 * guesses or stolen-refresh-token replays at zero cost. This class is
 * that floor.
 *
 * <h2>Counter model</h2>
 *
 * Two dimensions per call:
 * <ul>
 *   <li><b>Per-identifier</b> — bucket keyed by the login identifier
 *       (email or msisdn). Slows targeted brute-force against one
 *       account.</li>
 *   <li><b>Per-IP</b> — bucket keyed by the caller's source IP.
 *       Slows a single host spraying many accounts. The
 *       {@code X-Forwarded-For} header (set by the api-gateway) takes
 *       precedence over {@link jakarta.servlet.ServletRequest#getRemoteAddr}.</li>
 * </ul>
 *
 * Both counters live in Redis under {@code auth:rl:<kind>:<dimension>:<value>}
 * with the configured TTL (fixed-window). When either bucket exceeds its
 * cap the call is rejected with a {@link RateLimitedException} carrying
 * the seconds the caller should wait before retrying.
 *
 * <p><b>Fail-open</b>: if Redis is unreachable the limiter logs and lets
 * the call through. The auth path itself already enforces correctness
 * (password match / refresh-token rotation / family revocation), so a
 * brief Redis hiccup shouldn't lock honest users out. Compare to the
 * payment-service velocity gate which is intentionally fail-CLOSED —
 * different threat model (money movement vs. attempted access).
 */
@Service
@Slf4j
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "auth:rl:";

    private final int loginPerIdentifierMax;
    private final int loginPerIpMax;
    private final Duration loginWindow;

    private final int refreshPerIdentifierMax;
    private final int refreshPerIpMax;
    private final Duration refreshWindow;

    private final StringRedisTemplate redis;

    public LoginRateLimiter(
            @Value("${innbucks.auth-rate-limit.login.per-identifier-max:5}") int loginPerIdentifierMax,
            @Value("${innbucks.auth-rate-limit.login.per-ip-max:20}") int loginPerIpMax,
            @Value("${innbucks.auth-rate-limit.login.window-seconds:60}") int loginWindowSeconds,
            @Value("${innbucks.auth-rate-limit.refresh.per-identifier-max:10}") int refreshPerIdentifierMax,
            @Value("${innbucks.auth-rate-limit.refresh.per-ip-max:60}") int refreshPerIpMax,
            @Value("${innbucks.auth-rate-limit.refresh.window-seconds:60}") int refreshWindowSeconds,
            StringRedisTemplate redis) {
        validate("login.per-identifier-max", loginPerIdentifierMax);
        validate("login.per-ip-max", loginPerIpMax);
        validate("login.window-seconds", loginWindowSeconds);
        validate("refresh.per-identifier-max", refreshPerIdentifierMax);
        validate("refresh.per-ip-max", refreshPerIpMax);
        validate("refresh.window-seconds", refreshWindowSeconds);
        this.loginPerIdentifierMax = loginPerIdentifierMax;
        this.loginPerIpMax = loginPerIpMax;
        this.loginWindow = Duration.ofSeconds(loginWindowSeconds);
        this.refreshPerIdentifierMax = refreshPerIdentifierMax;
        this.refreshPerIpMax = refreshPerIpMax;
        this.refreshWindow = Duration.ofSeconds(refreshWindowSeconds);
        this.redis = redis;
    }

    /**
     * Pre-call gate for {@code POST /auth/login}. Increments both the
     * per-identifier and per-IP buckets atomically and throws if either
     * is over its cap. The identifier is lower-cased to avoid sibling
     * buckets for {@code Alice@x.com} and {@code alice@x.com}.
     *
     * @param identifier email or msisdn from the login request body;
     *                   {@code null}/blank is treated as a missing-key
     *                   case and only the per-IP bucket is incremented.
     * @param ip         client IP (X-Forwarded-For if present, else
     *                   {@code request.getRemoteAddr}).
     */
    public void checkLogin(String identifier, String ip) {
        enforce("login", identifier, ip,
                loginPerIdentifierMax, loginPerIpMax, loginWindow);
    }

    /**
     * Pre-call gate for {@code POST /auth/refresh}. Higher cap than
     * login because legitimate FE polls can hit refresh more often
     * (e.g. background app resume after the access token expires).
     *
     * @param identifier JWT subject extracted from the refresh token,
     *                   or {@code null} if the token is malformed / has
     *                   no subject. {@code null} skips the per-identifier
     *                   bucket but still counts the per-IP one — a
     *                   garbage stream of unparseable tokens from one
     *                   host is exactly what we want to throttle.
     */
    public void checkRefresh(String identifier, String ip) {
        enforce("refresh", identifier, ip,
                refreshPerIdentifierMax, refreshPerIpMax, refreshWindow);
    }

    private void enforce(String kind, String identifier, String ip,
                         int perIdentifierMax, int perIpMax, Duration window) {
        // Per-identifier bucket. Skipped when identifier is missing.
        if (identifier != null && !identifier.isBlank()) {
            long count = increment(key(kind, "id", normalise(identifier)), window);
            if (count > perIdentifierMax) {
                log.warn("Auth rate limit hit kind={} dimension=identifier identifier={} count={} max={}",
                        kind, mask(identifier), count, perIdentifierMax);
                throw new RateLimitedException(
                        "Too many " + kind + " attempts on this account; try again shortly",
                        (int) window.toSeconds());
            }
        }
        // Per-IP bucket. Always counted (a known IP signal is always present).
        if (ip != null && !ip.isBlank()) {
            long count = increment(key(kind, "ip", ip), window);
            if (count > perIpMax) {
                log.warn("Auth rate limit hit kind={} dimension=ip ip={} count={} max={}",
                        kind, ip, count, perIpMax);
                throw new RateLimitedException(
                        "Too many " + kind + " attempts from this address; try again shortly",
                        (int) window.toSeconds());
            }
        }
    }

    private long increment(String key, Duration ttl) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return 0L;
            }
            if (count == 1L) {
                // Only set TTL on the first increment so the window
                // starts when the first attempt lands and a steady
                // stream doesn't keep extending it.
                redis.expire(key, ttl);
            }
            return count;
        } catch (RuntimeException ex) {
            // Fail open — see class javadoc.
            log.error("Redis auth rate-limiter unreachable key={}; allowing call through", key, ex);
            return 0L;
        }
    }

    private static String key(String kind, String dimension, String value) {
        return KEY_PREFIX + kind + ":" + dimension + ":" + value;
    }

    private static String normalise(String identifier) {
        return identifier.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Last-4-char tail of the identifier for log lines. Same intent as
     * {@code MsisdnMasking} in payment-service — high-cardinality logs
     * shouldn't carry full phone numbers / emails.
     */
    private static String mask(String identifier) {
        String s = identifier.trim();
        if (s.length() <= 4) return "***";
        return "***" + s.substring(s.length() - 4);
    }

    private static void validate(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "innbucks.auth-rate-limit." + name + " must be positive, got " + value);
        }
    }

    /**
     * Carries the retry-after-seconds hint the controller surfaces in
     * the 429 response body. Caller may also set the {@code Retry-After}
     * HTTP header from {@link #getRetryAfterSeconds()}.
     */
    public static class RateLimitedException extends RuntimeException {
        private final int retryAfterSeconds;

        public RateLimitedException(String message, int retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
