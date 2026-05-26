package com.innbucks.eventservice.client;

import com.innbucks.eventservice.dto.EventSeatCategoryResponseDTO;
import com.innbucks.eventservice.dto.EventSectionResponseDTO;
import com.innbucks.eventservice.dto.SeatCategorySectionDTO;
import com.innbucks.eventservice.dto.SeatCategoryServiceResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.Arrays;
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

        SeatCategoryServiceResponseDTO[] categories = restTemplate.getForObject(
                url, SeatCategoryServiceResponseDTO[].class);

        if (categories == null || categories.length == 0) {
            return Collections.emptyList();
        }

        return Arrays.stream(categories)
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
