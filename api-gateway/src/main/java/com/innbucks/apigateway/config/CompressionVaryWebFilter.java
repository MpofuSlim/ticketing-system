package com.innbucks.apigateway.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adds {@code Vary: Accept-Encoding} to every response whose type the gateway
 * compresses ({@code server.compression.mime-types}).
 *
 * <p>Netty's compressor encodes the body but does not declare that the
 * representation depends on {@code Accept-Encoding}, so a shared cache that
 * stored a gzip'd copy could hand it to a client that never asked for gzip.
 * It is added whether or not THIS body crossed the size threshold: the next
 * response for the same URL may, and a cache keys on the header, not the size.
 * Appended, never replacing the CORS {@code Vary} values already present.
 */
@Component
public class CompressionVaryWebFilter implements WebFilter, Ordered {

    private final boolean enabled;
    private final List<MimeType> compressedTypes;

    public CompressionVaryWebFilter(Environment environment) {
        Binder binder = Binder.get(environment);
        this.enabled = binder.bind("server.compression.enabled", Boolean.class).orElse(false);
        this.compressedTypes = binder.bind("server.compression.mime-types", Bindable.listOf(String.class))
                .orElse(List.of()).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MimeType::valueOf)
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (enabled && !compressedTypes.isEmpty()) {
            ServerHttpResponse response = exchange.getResponse();
            response.beforeCommit(() -> {
                addVaryIfCompressible(response.getHeaders());
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    void addVaryIfCompressible(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        if (type == null || compressedTypes.stream().noneMatch(t -> t.isCompatibleWith(type))) {
            return;
        }
        boolean present = headers.getOrEmpty(HttpHeaders.VARY).stream()
                .flatMap(v -> List.of(v.split(",")).stream())
                .anyMatch(v -> v.trim().equalsIgnoreCase(HttpHeaders.ACCEPT_ENCODING));
        if (!present) {
            headers.add(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
