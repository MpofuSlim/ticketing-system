package com.innbucks.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/**
 * Resolves the bucket key the gateway's RequestRateLimiter charges against.
 * Authenticated requests are keyed by their bearer token (so a logged-in
 * user's quota stays consistent across IP changes); everyone else is keyed
 * by client IP. Falls back to a single shared "anonymous" bucket if neither
 * is available, which is a deliberately tight quota.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver gatewayKeyResolver() {
        return exchange -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // The token itself is the key; identical tokens => one bucket.
                return Mono.just("token:" + authHeader.substring(7));
            }
            if (exchange.getRequest().getRemoteAddress() != null) {
                return Mono.just("ip:"
                        + exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
            }
            return Mono.just("anonymous");
        };
    }
}
