package com.innbucks.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersWebFilterTest {

    private static final WebFilterChain PASS_THROUGH = exchange -> Mono.empty();

    @Test
    void filter_stampsAllAlwaysOnSecurityHeaders() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter(
                "DENY", "max-age=31536000; includeSubDomains",
                "strict-origin-when-cross-origin", "geolocation=(), camera=(), microphone=(), payment=()",
                "", false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/events").build());

        filter.filter(exchange, PASS_THROUGH).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
        assertThat(headers.getFirst("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        assertThat(headers.getFirst("Permissions-Policy"))
                .isEqualTo("geolocation=(), camera=(), microphone=(), payment=()");
        // CSP is opt-in (empty) so neither header is set.
        assertThat(headers.getFirst("Content-Security-Policy")).isNull();
        assertThat(headers.getFirst("Content-Security-Policy-Report-Only")).isNull();
    }

    @Test
    void filter_emitsCspInReportOnlyMode_byDefault() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter(
                "DENY", "max-age=31536000",
                "strict-origin-when-cross-origin", "geolocation=(), camera=(), microphone=(), payment=()",
                "default-src 'self'", false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/events").build());

        filter.filter(exchange, PASS_THROUGH).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("Content-Security-Policy-Report-Only"))
                .isEqualTo("default-src 'self'");
        assertThat(headers.getFirst("Content-Security-Policy")).isNull();
    }

    @Test
    void filter_emitsEnforcingCsp_whenCspEnforceTrue() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter(
                "DENY", "max-age=31536000",
                "strict-origin-when-cross-origin", "geolocation=(), camera=(), microphone=(), payment=()",
                "default-src 'self'", true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/events").build());

        filter.filter(exchange, PASS_THROUGH).block();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(headers.getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'self'");
        assertThat(headers.getFirst("Content-Security-Policy-Report-Only")).isNull();
    }

    @Test
    void filter_respectsFrameOptionsOverride() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter(
                "SAMEORIGIN", "max-age=31536000",
                "strict-origin-when-cross-origin", "geolocation=(), camera=(), microphone=(), payment=()",
                "", false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/events").build());

        filter.filter(exchange, PASS_THROUGH).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("SAMEORIGIN");
    }

    @Test
    void filter_doesNotClobberHeaderSetByDownstream() {
        SecurityHeadersWebFilter filter = new SecurityHeadersWebFilter(
                "DENY", "max-age=31536000",
                "strict-origin-when-cross-origin", "geolocation=(), camera=(), microphone=(), payment=()",
                "", false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/events").build());
        // Simulate a downstream service that explicitly emitted SAMEORIGIN
        // (e.g. an endpoint that legitimately needs same-origin iframe embedding).
        exchange.getResponse().getHeaders().set("X-Frame-Options", "SAMEORIGIN");

        filter.filter(exchange, PASS_THROUGH).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("SAMEORIGIN");
    }
}
