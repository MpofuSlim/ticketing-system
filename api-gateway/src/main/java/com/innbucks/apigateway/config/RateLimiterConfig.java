package com.innbucks.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

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
}
