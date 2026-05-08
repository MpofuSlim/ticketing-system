package com.innbucks.apigateway.config;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Overrides Swagger UI's swagger-initializer.js to inject a requestInterceptor
 * that adds {@code ngrok-skip-browser-warning: 1} on every API call made from
 * the UI. Without this header, ngrok-free returns 403 (no body) on browser-
 * originated POST/PUT/DELETE requests, breaking Swagger's "Try it out" flow
 * when the gateway is exposed via an ngrok tunnel.
 *
 * <p>Runs before SpringDoc's resource handler — when the path matches, the
 * filter writes the custom JS directly and short-circuits the chain.
 */
@Component
public class SwaggerNgrokInterceptorFilter implements WebFilter, Ordered {

    private static final String CUSTOM_INITIALIZER =
            "window.onload = function() {\n" +
            "  window.ui = SwaggerUIBundle({\n" +
            "    configUrl: \"/v3/api-docs/swagger-config\",\n" +
            "    dom_id: \"#swagger-ui\",\n" +
            "    deepLinking: true,\n" +
            "    presets: [\n" +
            "      SwaggerUIBundle.presets.apis,\n" +
            "      SwaggerUIStandalonePreset\n" +
            "    ],\n" +
            "    plugins: [\n" +
            "      SwaggerUIBundle.plugins.DownloadUrl\n" +
            "    ],\n" +
            "    layout: \"StandaloneLayout\",\n" +
            "    persistAuthorization: true,\n" +
            "    requestInterceptor: function(request) {\n" +
            "      request.headers['ngrok-skip-browser-warning'] = '1';\n" +
            "      return request;\n" +
            "    }\n" +
            "  });\n" +
            "};\n";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.endsWith("/swagger-initializer.js")) {
            byte[] bytes = CUSTOM_INITIALIZER.getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            exchange.getResponse().getHeaders().setContentType(
                    MediaType.valueOf("application/javascript"));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Run before SpringDoc's resource handlers so we can short-circuit.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
