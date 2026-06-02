package com.innbucks.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Stamps standard browser security headers on every gateway response so
 * downstream clients (the FE in particular) get safe defaults without each
 * backend having to remember them.
 *
 * <p>Three headers are always set:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — disables MIME sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — blocks clickjacking via iframe embedding.
 *       Override with {@code SECURITY_HEADERS_FRAME_OPTIONS=SAMEORIGIN} if the
 *       FE ever needs to iframe its own pages.</li>
 *   <li>{@code Strict-Transport-Security: max-age=31536000; includeSubDomains} —
 *       harmless on plain HTTP (no-op), force-locks browsers to HTTPS once TLS
 *       is in front of the gateway.</li>
 * </ul>
 *
 * <p>Content-Security-Policy is opt-in via env var {@code SECURITY_HEADERS_CSP}.
 * It ships in {@code Report-Only} mode (header
 * {@code Content-Security-Policy-Report-Only}) so violations are reported
 * without breaking the FE during the bake-in window. Switch to enforcing mode
 * (set {@code SECURITY_HEADERS_CSP_ENFORCE=true}) once the FE team has
 * validated the policy.
 *
 * <p>{@code setIfAbsent} is used so a downstream service that emits its own
 * header value isn't clobbered — useful for {@code X-Frame-Options} where a
 * specific endpoint might legitimately want {@code SAMEORIGIN}.
 *
 * <p>Runs LAST in the WebFilter chain (lowest precedence) so CORS preflight
 * headers and the per-route filters are already attached when this stamps in.
 */
@Component
public class SecurityHeadersWebFilter implements WebFilter, Ordered {

    private final String frameOptions;
    private final String hsts;
    private final String csp;
    private final boolean cspEnforce;

    public SecurityHeadersWebFilter(
            @Value("${security.headers.frame-options:DENY}") String frameOptions,
            @Value("${security.headers.hsts:max-age=31536000; includeSubDomains}") String hsts,
            @Value("${security.headers.csp:}") String csp,
            @Value("${security.headers.csp-enforce:false}") boolean cspEnforce) {
        this.frameOptions = frameOptions;
        this.hsts = hsts;
        this.csp = csp;
        this.cspEnforce = cspEnforce;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Stamp BEFORE the downstream proxy fires. WebFlux defers the response
        // commit, so the response headers we set here travel back to the client
        // once the chain unwinds.
        HttpHeaders headers = exchange.getResponse().getHeaders();
        putIfAbsent(headers, "X-Content-Type-Options", "nosniff");
        putIfAbsent(headers, "X-Frame-Options", frameOptions);
        putIfAbsent(headers, "Strict-Transport-Security", hsts);
        if (csp != null && !csp.isBlank()) {
            String name = cspEnforce
                    ? "Content-Security-Policy"
                    : "Content-Security-Policy-Report-Only";
            putIfAbsent(headers, name, csp);
        }
        return chain.filter(exchange);
    }

    private static void putIfAbsent(HttpHeaders headers, String name, String value) {
        if (headers.getFirst(name) == null) {
            headers.set(name, value);
        }
    }

    @Override
    public int getOrder() {
        // After CorrelationIdWebFilter (HIGHEST) and SwaggerNgrokInterceptorFilter
        // (HIGHEST+1). LOWEST_PRECEDENCE keeps us out of CORS preflight processing.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
