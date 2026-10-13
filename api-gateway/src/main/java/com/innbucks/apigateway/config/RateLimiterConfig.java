package com.innbucks.apigateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Resolves the bucket key the gateway's RequestRateLimiter charges against.
 * Authenticated requests are keyed by their bearer token (so a logged-in
 * user's quota stays consistent across IP changes); everyone else is keyed
 * by client IP. Falls back to a single shared "anonymous" bucket if neither
 * is available, which is a deliberately tight quota.
 *
 * <p>Every IP key comes from {@link #gatewayRemoteAddressResolver}, which reads
 * the nginx-set {@code X-Real-IP} ({@link ClientIpResolver}). A new IP-keyed
 * resolver must take its address from that bean too, never from
 * {@code X-Forwarded-For} or the request's remote address.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves the real client IP for the per-IP rate-limit key: the
     * {@code X-Real-IP} the host nginx sets, parsed as a literal address only.
     *
     * <p>The gateway sits behind Cloudflare and the host nginx, so the socket
     * peer is nginx, not the caller, and {@code X-Forwarded-For} is unusable
     * here: the {@code framework} forward-headers strategy rewrites the remote
     * address from its caller-chosen left-most entry and strips the header.
     * {@link ClientIpResolver} has the full reasoning, and
     * {@code deploy/k8s/README.md} §3 the nginx config it depends on.
     */
    @Bean
    public RemoteAddressResolver gatewayRemoteAddressResolver(MeterRegistry meterRegistry) {
        return new ClientIpResolver(meterRegistry);
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
            return ipKey(gatewayRemoteAddressResolver, exchange, "anonymous");
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
        return exchange -> ipKey(gatewayRemoteAddressResolver, exchange, "anonymous-payments-innbucks");
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
        return exchange -> ipKey(gatewayRemoteAddressResolver, exchange, "anonymous-pre-auth");
    }

    /**
     * {@code ip:<address>} for the client address, or {@code fallbackKey} when
     * there is none. An UNRESOLVED address has none: that is what the
     * forward-headers transformer builds from {@code X-Forwarded-For}, and it
     * must never become a key of its own.
     */
    private static Mono<String> ipKey(RemoteAddressResolver resolver, ServerWebExchange exchange,
                                      String fallbackKey) {
        InetSocketAddress remote = resolver.resolve(exchange);
        if (remote != null && remote.getAddress() != null) {
            return Mono.just("ip:" + remote.getAddress().getHostAddress());
        }
        return Mono.just(fallbackKey);
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
