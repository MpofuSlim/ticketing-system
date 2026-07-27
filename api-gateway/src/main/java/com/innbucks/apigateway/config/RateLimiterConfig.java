package com.innbucks.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Resolves the bucket key the gateway's RequestRateLimiter charges against.
 * Authenticated requests are keyed by their bearer token (so a logged-in
 * user's quota stays consistent across IP changes); everyone else is keyed
 * by client IP. Falls back to a single shared "anonymous" bucket if neither
 * is available, which is a deliberately tight quota.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves the real client IP for the per-IP rate-limit key.
     *
     * <p>The gateway sits behind a reverse proxy / ingress in every hosted
     * environment, so the socket peer is the proxy — not the caller. Without
     * honouring {@code X-Forwarded-For} every anonymous request collapses into
     * a single "proxy IP" bucket, which both defeats the limit (one global
     * quota for the whole internet) and lets a single abuser starve everyone
     * else. {@code maxTrustedIndex(n)} trusts only the {@code n} right-most XFF
     * entries (the ones our own proxies appended), so a client cannot widen its
     * quota by spoofing extra left-most entries.
     *
     * <p>{@code gateway.trusted-proxy-count} is the number of trusted proxies in
     * front of the gateway (default 1 = a single ingress). Set it to {@code 0}
     * when the gateway is directly internet-exposed (no trusted proxy) so
     * {@code X-Forwarded-For} is ignored entirely and the key falls back to the
     * socket peer — otherwise a client could forge the header to mint unlimited
     * buckets.
     */
    @Bean
    public RemoteAddressResolver gatewayRemoteAddressResolver(
            @Value("${gateway.trusted-proxy-count:1}") int trustedProxyCount) {
        if (trustedProxyCount <= 0) {
            // RemoteAddressResolver's default resolve() returns the socket peer
            // and ignores X-Forwarded-For — exactly the no-trusted-proxy case.
            return new RemoteAddressResolver() {};
        }
        return XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyCount);
    }

    // @Primary so requestRateLimiterGatewayFilterFactory's KeyResolver
    // autowire picks this one as the default. Other KeyResolver beans
    // (e.g. paymentsInnbucksIpKeyResolver below) must be referenced
    // explicitly by name via SpEL in the route YAML
    // (key-resolver: "#{@paymentsInnbucksIpKeyResolver}").
    @Primary
    @Bean
    public KeyResolver gatewayKeyResolver(RemoteAddressResolver gatewayRemoteAddressResolver) {
        return exchange -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // The token itself is the key; identical tokens => one bucket.
                return Mono.just("token:" + authHeader.substring(7));
            }
            InetSocketAddress remote = gatewayRemoteAddressResolver.resolve(exchange);
            if (remote != null && remote.getAddress() != null) {
                return Mono.just("ip:" + remote.getAddress().getHostAddress());
            }
            return Mono.just("anonymous");
        };
    }

    /**
     * IP-ONLY resolver for the public, no-bearer checkout route
     * (POST /payments/innbucks). The default {@link #gatewayKeyResolver}
     * also falls back to IP when there's no bearer, but that fallback chain
     * is implicit — a future refactor that drops the IP arm (or shares the
     * route with a bearer-keyed flow) would silently widen the bucket. This
     * resolver hardcodes IP-keying so the public payment-initiation route
     * cannot be widened by accident. Falls back to a deliberately tight
     * shared "anonymous" bucket only when the remote address is genuinely
     * unresolvable (defensive — shouldn't happen behind a real proxy).
     */
    @Bean
    public KeyResolver paymentsInnbucksIpKeyResolver(RemoteAddressResolver gatewayRemoteAddressResolver) {
        return exchange -> {
            InetSocketAddress remote = gatewayRemoteAddressResolver.resolve(exchange);
            if (remote != null && remote.getAddress() != null) {
                return Mono.just("ip:" + remote.getAddress().getHostAddress());
            }
            return Mono.just("anonymous-payments-innbucks");
        };
    }

    /**
     * IP-ONLY resolver for the pre-auth abuse-sensitive routes (OTP, MFA,
     * forgot/reset-password). These routes were previously on the default
     * {@link #gatewayKeyResolver}, on the assumption that pre-auth calls carry
     * no bearer and therefore fall through to the IP arm. That assumption is
     * attacker-controlled: the default resolver keys on the RAW Authorization
     * header without validating it, so a caller who attaches a rotating
     * garbage {@code Bearer} value mints a fresh bucket per request and the
     * per-IP cap — the only fleet-level control on real-money SMS sends —
     * never engages. Hardcoding IP-keying here closes that bypass the same way
     * {@link #paymentsInnbucksIpKeyResolver} does for the public checkout
     * route.
     *
     * <p>Trade-off (deliberate): callers behind one shared NAT share a bucket
     * on these routes. They are low-frequency, human-paced actions and the
     * replenish/burst is env-tunable ({@code AUTH_OTP_RATE_LIMIT_*}), so a
     * shared office IP tripping the cap is throttled, not locked out — and the
     * per-account / per-msisdn caps downstream are unaffected.
     */
    @Bean
    public KeyResolver preAuthIpKeyResolver(RemoteAddressResolver gatewayRemoteAddressResolver) {
        return exchange -> {
            InetSocketAddress remote = gatewayRemoteAddressResolver.resolve(exchange);
            if (remote != null && remote.getAddress() != null) {
                return Mono.just("ip:" + remote.getAddress().getHostAddress());
            }
            return Mono.just("anonymous-pre-auth");
        };
    }

    /**
     * Fail-SAFE rate limiter for the SMS-cost / payment routes (OWASP A04). It
     * wraps the auto-configured {@link RedisRateLimiter}: Redis stays the
     * cross-instance source of truth, but when Redis is unreachable — where the
     * stock limiter fails OPEN and drops all throttling — it falls back to an
     * in-memory Bucket4j bucket at the SAME replenish/burst the route
     * configured. See {@link ResilientRedisRateLimiter} for the detection and
     * boundedness details.
     *
     * <p>{@code autowireCandidate = false} keeps this bean out of the
     * by-type {@code RateLimiter} injection that Spring Cloud Gateway's
     * {@code RequestRateLimiterGatewayFilterFactory} uses to pick the DEFAULT
     * limiter — so every route that does not opt in explicitly stays on the
     * auto-configured {@code RedisRateLimiter} (fail-open), and login is never
     * throttled by a Redis outage. Routes opt in by name with
     * {@code rate-limiter: "#{@resilientRedisRateLimiter}"}; the SpEL bean
     * reference resolves by name regardless of {@code autowireCandidate}.
     *
     * <p>The bucket map is bounded by {@code max-buckets} (LRU eviction) and
     * {@code bucket-idle-minutes} (idle expiry) so it cannot leak memory under
     * a key-rotating flood during an outage.
     */
    @Bean(autowireCandidate = false)
    public ResilientRedisRateLimiter resilientRedisRateLimiter(
            RedisRateLimiter redisRateLimiter,
            @Value("${innbucks.ratelimit.fallback.max-buckets:50000}") long maxBuckets,
            @Value("${innbucks.ratelimit.fallback.bucket-idle-minutes:10}") long bucketIdleMinutes) {
        return new ResilientRedisRateLimiter(
                redisRateLimiter, maxBuckets, Duration.ofMinutes(bucketIdleMinutes));
    }
}
