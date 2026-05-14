package com.innbucks.seatservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin wrapper around RestClient calling booking-service. seat-service is on
 * the analytics path only; booking-service downtime should NOT take this
 * endpoint down. {@link #fetchBookingsByCategory} returns an empty Optional
 * on any failure (timeout, 5xx, parsing) so the caller can degrade
 * gracefully and tell the consumer that booking data is stale.
 */
@Component
@Slf4j
public class BookingServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BookingServiceClient(
            @Value("${booking-service.base-url:http://localhost:8084}") String baseUrl,
            @Value("${booking-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${booking-service.read-timeout-ms:5000}") int readTimeoutMs,
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

    public Optional<List<CategoryBookingDTO>> fetchBookingsByCategory(UUID categoryId, String authHeader) {
        return fetch("/bookings/by-category/" + categoryId, authHeader, "categoryId=" + categoryId);
    }

    public Optional<List<CategoryBookingDTO>> fetchBookingsByEvent(UUID eventId, String authHeader) {
        return fetch("/bookings/by-event/" + eventId, authHeader, "eventId=" + eventId);
    }

    private Optional<List<CategoryBookingDTO>> fetch(String path, String authHeader, String logCtx) {
        try {
            String body = restClient.get()
                    .uri(path)
                    .headers(headers -> {
                        if (authHeader != null && !authHeader.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.of(List.of());
            }
            // Manually deserialise so we can keep ApiResult<List<CategoryBookingDTO>>'s
            // generic intact across the wire without bringing booking-service's DTO
            // class onto seat-service's classpath.
            ApiResult<List<CategoryBookingDTO>> envelope = objectMapper.readValue(
                    body,
                    new TypeReference<ApiResult<List<CategoryBookingDTO>>>() {}
            );
            return Optional.of(envelope.getData() != null ? envelope.getData() : List.of());
        } catch (ResourceAccessException e) {
            // Connect / read timeout, DNS, refused connection — booking-service down.
            log.warn("booking-service unreachable {} cause={}", logCtx, e.toString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("booking-service returned an error {} cause={}", logCtx, e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse booking-service response {} cause={}", logCtx, e.toString());
            return Optional.empty();
        }
    }
}
