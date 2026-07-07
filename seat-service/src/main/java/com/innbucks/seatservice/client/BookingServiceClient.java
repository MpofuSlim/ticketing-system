package com.innbucks.seatservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.config.CorrelationIdPropagatingInterceptor;
import com.innbucks.seatservice.dto.ApiResult;
import com.innbucks.seatservice.dto.CategoryActiveCountDTO;
import com.innbucks.seatservice.dto.CategoryBookingDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final String internalToken;

    public BookingServiceClient(
            @LoadBalanced RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${booking-service.base-url:http://booking-service}") String baseUrl,
            @Value("${booking-service.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${booking-service.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${innbucks.internal-api-token}") String internalToken,
            ObjectMapper objectMapper) {
        this.internalToken = internalToken;
        // Clone the load-balanced builder so "booking-service" resolves through
        // Eureka; clone() preserves the LB interceptor alongside our per-client
        // request factory and correlation-id interceptor.
        this.restClient = loadBalancedRestClientBuilder.clone()
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

    /**
     * Fetches the live active-booking count per category so callers can render
     * {@code availableSeats = totalSeats − count}. Returns the counts keyed by
     * categoryId; categories with no active bookings are absent from the map
     * (the caller reads a missing key as "full capacity remaining").
     *
     * <p>A01: this is the internal-only {@code GET /bookings/internal/categories/active-counts}
     * endpoint. It carries the shared {@code X-Internal-Token} (service identity,
     * never a user JWT) rather than an Authorization header — availability is
     * rendered on public category listings where there is no user token, so a
     * service-to-service credential is the only thing that works AND keeps the
     * endpoint non-anonymous. On any failure (timeout, 5xx, parse, 401) an empty
     * Optional is returned so the category read degrades to its stored mirror
     * rather than failing — never let booking-service downtime 500 a public
     * category listing.
     */
    public Optional<Map<UUID, Long>> fetchActiveCountsByCategories(Collection<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Optional.of(Map.of());
        }
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/bookings/internal/categories/active-counts");
                        for (UUID id : categoryIds) {
                            uriBuilder.queryParam("categoryIds", id.toString());
                        }
                        return uriBuilder.build();
                    })
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return Optional.of(Map.of());
            }
            ApiResult<List<CategoryActiveCountDTO>> envelope = objectMapper.readValue(
                    body,
                    new TypeReference<ApiResult<List<CategoryActiveCountDTO>>>() {}
            );
            Map<UUID, Long> counts = new HashMap<>();
            if (envelope.getData() != null) {
                for (CategoryActiveCountDTO row : envelope.getData()) {
                    if (row.getCategoryId() != null) {
                        counts.put(row.getCategoryId(), row.getCount());
                    }
                }
            }
            return Optional.of(counts);
        } catch (ResourceAccessException e) {
            log.warn("booking-service unreachable for category counts categoryIds={} cause={}",
                    categoryIds, e.toString());
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("booking-service returned an error for category counts categoryIds={} cause={}",
                    categoryIds, e.toString());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse booking-service category counts categoryIds={} cause={}",
                    categoryIds, e.toString());
            return Optional.empty();
        }
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
