package com.innbucks.eventservice.client;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import com.innbucks.eventservice.dto.EventSectionResponseDTO;
import com.innbucks.eventservice.dto.SeatCategorySectionDTO;
import com.innbucks.eventservice.dto.SeatCategoryServiceResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wraps the outbound call to seat-service in a circuit breaker (Resilience4j
 * via Spring Cloud's abstraction) plus retry. Falls back to an empty
 * category list when the call fails or the breaker is open — event-service
 * still returns the event itself, just without enriched seat detail.
 */
@Component
@Slf4j
public class SeatCategoryGateway {

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final String seatServiceBaseUrl;

    public SeatCategoryGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Value("${seat-service.base-url:http://seat-service}") String seatServiceBaseUrl) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("seatCategories");
        this.seatServiceBaseUrl = seatServiceBaseUrl;
    }

    public List<EventSeatCategoryResponseDTO> fetchForEvent(UUID eventId) {
        return circuitBreaker.run(
                () -> doFetch(eventId),
                throwable -> {
                    log.warn("seatCategories breaker fallback eventId={}",
                            eventId, throwable);
                    return Collections.emptyList();
                }
        );
    }

    private List<EventSeatCategoryResponseDTO> doFetch(UUID eventId) {
        String url = UriComponentsBuilder
                .fromUriString(seatServiceBaseUrl)
                .path("/seat-categories")
                .queryParam("eventId", eventId)
                .toUriString();

        // seat-service wraps every payload in its ApiResult envelope
        // {code, message, data: [...]}; deserialise into that and read .data.
        // (Direct array deserialisation died on every call after seat-service
        // moved to the standard envelope — every event lookup fell through to
        // the breaker fallback with an empty category list.)
        ResponseEntity<ApiResult<List<SeatCategoryServiceResponseDTO>>> response =
                restTemplate.exchange(url, HttpMethod.GET, null,
                        new ParameterizedTypeReference<>() {});
        ApiResult<List<SeatCategoryServiceResponseDTO>> envelope = response.getBody();
        List<SeatCategoryServiceResponseDTO> categories =
                envelope == null ? null : envelope.getData();
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }

        return categories.stream()
                .map(category -> EventSeatCategoryResponseDTO.builder()
                        .name(category.getName())
                        .description(category.getDescription())
                        .categoryPrice(category.getPrice())
                        .sections(mapSectionsWithPrice(category.getSections(), category.getPrice()))
                        .build())
                .collect(Collectors.toList());
    }

    private List<EventSectionResponseDTO> mapSectionsWithPrice(
            List<SeatCategorySectionDTO> sections, BigDecimal price) {
        if (sections == null || sections.isEmpty()) {
            return Collections.emptyList();
        }
        return sections.stream()
                .map(section -> EventSectionResponseDTO.builder()
                        .section(section.getSection())
                        .seatCount(section.getSeatCount())
                        .price(price)
                        .build())
                .collect(Collectors.toList());
    }
}
