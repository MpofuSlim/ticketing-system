package com.innbucks.seatservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.EventLookupDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin RestClient wrapper around event-service. We only need the tenantId for
 * ownership checks (a category can only be created/deleted by the user who
 * owns the underlying event, modulo SUPER_ADMIN bypass), so this client only
 * exposes the lookup, not the full event payload.
 */
@Component
@Slf4j
public class EventServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public EventServiceClient(
            @Value("${event-service.base-url:http://localhost:8082}") String baseUrl,
            @Value("${event-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${event-service.read-timeout-ms:5000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(buildRequestFactory(connectTimeoutMs, readTimeoutMs))
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
        this.objectMapper = objectMapper;
    }

    private static org.springframework.http.client.SimpleClientHttpRequestFactory buildRequestFactory(int connectMs, int readMs) {
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }

    public Optional<EventLookupDTO> fetchEvent(UUID eventId, String authHeader) {
        try {
            String body = restClient.get()
                    .uri("/events/" + eventId)
                    .headers(headers -> {
                        if (authHeader != null && !authHeader.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            if (body == null) return Optional.empty();
            ApiResult<EventLookupDTO> envelope = objectMapper.readValue(
                    body, new TypeReference<ApiResult<EventLookupDTO>>() {});
            return Optional.ofNullable(envelope.getData());
        } catch (ResourceAccessException e) {
            log.warn("event-service unreachable eventId={} cause={}", eventId, e.toString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("event-service returned an error eventId={} cause={}", eventId, e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse event-service response eventId={} cause={}", eventId, e.toString());
            return Optional.empty();
        }
    }
}
