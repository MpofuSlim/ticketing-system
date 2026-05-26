package com.innbucks.eventservice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.eventservice.dto.BookingActiveCountDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls booking-service's GET /bookings/active-counts to fetch the count of
 * active (PENDING + CONFIRMED) booking items per event. event-service uses the
 * result to compute availableTickets = totalCapacity − count on every event
 * response so the API tallies in real time. Wrapped in a circuit breaker —
 * fallback returns an empty map and the stored availableTickets is used.
 */
@Component
@Slf4j
public class BookingGateway {

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final String bookingServiceBaseUrl;

    public BookingGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectMapper objectMapper,
            @Value("${booking-service.base-url:http://booking-service}") String bookingServiceBaseUrl) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("bookingActiveCounts");
        this.objectMapper = objectMapper;
        this.bookingServiceBaseUrl = bookingServiceBaseUrl;
    }

    public Map<UUID, Long> activeCountsByEventIds(Collection<UUID> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return circuitBreaker.run(
                () -> doFetch(eventIds),
                throwable -> {
                    log.warn("bookingActiveCounts breaker fallback eventIds={}",
                            eventIds, throwable);
                    return Collections.emptyMap();
                }
        );
    }

    private Map<UUID, Long> doFetch(Collection<UUID> eventIds) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(bookingServiceBaseUrl)
                .path("/bookings/active-counts");
        for (UUID id : eventIds) {
            builder.queryParam("eventIds", id.toString());
        }

        // Booking-service returns ApiResult<List<EventActiveCountDTO>>. Read it
        // as a generic map first to avoid pulling in cross-service typed envelope
        // generics, then map the data list.
        Map<String, Object> raw = restTemplate.getForObject(builder.toUriString(), Map.class);
        if (raw == null) {
            return Collections.emptyMap();
        }
        Object data = raw.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return Collections.emptyMap();
        }
        List<BookingActiveCountDTO> rows = objectMapper.convertValue(
                list, new TypeReference<List<BookingActiveCountDTO>>() {});
        Map<UUID, Long> result = new HashMap<>();
        for (BookingActiveCountDTO row : rows) {
            if (row.getEventId() != null) {
                result.put(row.getEventId(), row.getCount());
            }
        }
        return result;
    }
}
