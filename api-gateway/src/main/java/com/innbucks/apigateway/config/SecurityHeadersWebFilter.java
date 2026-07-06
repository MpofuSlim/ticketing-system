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
 * <p>Five headers are always set:
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — disables MIME sniffing.</li>
 *   <li>{@code X-Frame-Options: DENY} — blocks clickjacking via iframe embedding.
 *       Override with {@code SECURITY_HEADERS_FRAME_OPTIONS=SAMEORIGIN} if the
 *       FE ever needs to iframe its own pages.</li>
 *   <li>{@code Strict-Transport-Security: max-age=31536000; includeSubDomains} —
 *       harmless on plain HTTP (no-op), force-locks browsers to HTTPS once TLS
 *       is in front of the gateway.</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — sends only the
 *       origin (no path/query) on cross-origin navigations, so URLs carrying
 *       tokens/IDs don't leak in the {@code Referer}. Override with
 *       {@code SECURITY_HEADERS_REFERRER_POLICY}.</li>
 *   <li>{@code Permissions-Policy: geolocation=(), camera=(), microphone=(),
 *       payment=()} — denies these powerful browser features to all origins
 *       (empty allowlist). Override with {@code SECURITY_HEADERS_PERMISSIONS_POLICY}.</li>
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
    private final String referrerPolicy;
    private final String permissionsPolicy;
    private final String csp;
    private final boolean cspEnforce;

    public SecurityHeadersWebFilter(
            @Value("${security.headers.frame-options:DENY}") String frameOptions,
            @Value("${security.headers.hsts:max-age=31536000; includeSubDomains}") String hsts,
            @Value("${security.headers.referrer-policy:strict-origin-when-cross-origin}") String referrerPolicy,
            @Value("${security.headers.permissions-policy:geolocation=(), camera=(), microphone=(), payment=()}") String permissionsPolicy,
            @Value("${security.headers.csp:}") String csp,
            @Value("${security.headers.csp-enforce:false}") boolean cspEnforce) {
        this.frameOptions = frameOptions;
        this.hsts = hsts;
        this.referrerPolicy = referrerPolicy;
        this.permissionsPolicy = permissionsPolicy;
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
        putIfAbsent(headers, "Referrer-Policy", referrerPolicy);
        putIfAbsent(headers, "Permissions-Policy", permissionsPolicy);
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
