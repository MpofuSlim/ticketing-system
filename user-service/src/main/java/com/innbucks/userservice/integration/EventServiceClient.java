package com.innbucks.userservice.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.innbucks.userservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.userservice.config.PooledHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Thin client over event-service's internal API (V53). Used before a business's
 * ticketing service is suspended, which is refused while its organizers have
 * an event on sale (owner decision, 2026-11-03).
 */
@Component
@Slf4j
public class EventServiceClient {

    private final RestClient http;
    private final String internalToken;

    public EventServiceClient(@LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
                              @Value("${event-service.base-url:http://event-service}") String baseUrl,
                              @Value("${event-service.connect-timeout-ms:2000}") int connectTimeoutMs,
                              @Value("${event-service.read-timeout-ms:3000}") int readTimeoutMs,
                              @Value("${innbucks.internal-api-token:}") String internalToken,
                              PooledHttpClient pooledHttpClient) {
        // The module's shared pool, this client's own timeouts (CLAUDE.md,
        // "Outbound HTTP clients are pooled"); "event-service" resolves through
        // the static discovery map.
        this.http = loadBalancedRestClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(pooledHttpClient.requestFactory(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.internalToken = internalToken;
    }

    /**
     * How many published, not-yet-ended events these organizers have, or
     * {@link OptionalLong#empty()} when event-service did not answer. STRICT:
     * every failure (no token, an error status, the network, a body without the
     * count) is "unknown", never zero, because zero would let a suspension
     * through while events are on sale.
     */
    public OptionalLong liveEventCount(Collection<UUID> organizerUuids) {
        if (organizerUuids == null || organizerUuids.isEmpty()) {
            return OptionalLong.of(0);
        }
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("Live event count not answered; INTERNAL_API_TOKEN is not configured");
            return OptionalLong.empty();
        }
        String ids = organizerUuids.stream().map(UUID::toString).collect(Collectors.joining(","));
        try {
            ResponseEntity<LiveEventCountResponse> response = http.get()
                    .uri("/events/internal/live-event-count?organizerUuids={ids}", ids)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .toEntity(LiveEventCountResponse.class);
            LiveEventCountResponse body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.data() == null
                    || body.data().liveEvents() == null) {
                log.warn("Live event count not answered status={}", response.getStatusCode().value());
                return OptionalLong.empty();
            }
            return OptionalLong.of(body.data().liveEvents());
        } catch (Exception ex) {
            log.warn("Live event count not answered error={}", ex.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiveEventCountResponse(String code, String message, Count data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Count(Long liveEvents) {}
}
