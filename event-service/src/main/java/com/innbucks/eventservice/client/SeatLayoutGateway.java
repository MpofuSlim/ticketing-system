package com.innbucks.eventservice.client;

import com.innbucks.eventservice.dto.ApiResult;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asks seat-service to copy a series template's seat layout onto a new date
 * ({@code POST /seat-categories/internal/copy}, X-Internal-Token; TIC-70), and
 * to set one category's price on every future date of a series
 * ({@code POST /seat-categories/internal/price-by-name}; TIC-71).
 *
 * <p>The copy is idempotent on seat-service's side, so a call whose answer
 * was lost is simply made again by the next generator run. Never called with
 * a transaction open: the generator runs it between its two write phases.
 */
@Component
@Slf4j
public class SeatLayoutGateway {

    /** What a copy came to. */
    public enum Outcome {
        /** The target has the layout: copied now, or already. */
        DONE,
        /** seat-service answered no (4xx): the next run asks again. */
        REFUSED,
        /** No answer: the next run asks again. */
        UNAVAILABLE
    }

    /** The outcome and, when DONE, each template category's id on the date. */
    public record LayoutCopy(Outcome outcome, Map<UUID, UUID> categoryIds) {
        static LayoutCopy failed(Outcome outcome) {
            return new LayoutCopy(outcome, Map.of());
        }
    }

    private final RestTemplate restTemplate;
    private final String seatServiceBaseUrl;
    private final String internalToken;

    public SeatLayoutGateway(RestTemplate restTemplate,
                             @Value("${seat-service.base-url:http://seat-service}") String seatServiceBaseUrl,
                             @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.seatServiceBaseUrl = seatServiceBaseUrl;
        this.internalToken = internalToken;
    }

    public LayoutCopy copyLayout(UUID sourceEventId, UUID targetEventId, int maxTotalSeats) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceEventId", sourceEventId.toString());
        body.put("targetEventId", targetEventId.toString());
        body.put("maxTotalSeats", maxTotalSeats);
        try {
            ResponseEntity<ApiResult<CopiedLayout>> response = restTemplate.exchange(
                    seatServiceBaseUrl + "/seat-categories/internal/copy", HttpMethod.POST,
                    new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() { });
            ApiResult<CopiedLayout> envelope = response.getBody();
            CopiedLayout copied = envelope == null ? null : envelope.getData();
            if (copied == null || copied.getCategories() == null) {
                log.warn("Seat layout copy answered without a layout sourceEventId={} targetEventId={}",
                        sourceEventId, targetEventId);
                return LayoutCopy.failed(Outcome.UNAVAILABLE);
            }
            Map<UUID, UUID> ids = new LinkedHashMap<>();
            for (CopiedCategory category : copied.getCategories()) {
                if (category.getSourceCategoryId() != null && category.getCategoryId() != null) {
                    ids.put(category.getSourceCategoryId(), category.getCategoryId());
                }
            }
            return new LayoutCopy(Outcome.DONE, Map.copyOf(ids));
        } catch (HttpClientErrorException e) {
            log.warn("Seat layout copy refused sourceEventId={} targetEventId={} status={}",
                    sourceEventId, targetEventId, e.getStatusCode().value());
            return LayoutCopy.failed(Outcome.REFUSED);
        } catch (RestClientException e) {
            log.warn("Seat layout copy failed sourceEventId={} targetEventId={} cause={}",
                    sourceEventId, targetEventId, e.toString());
            return LayoutCopy.failed(Outcome.UNAVAILABLE);
        }
    }

    /** What a price-by-name call came to (TIC-71). */
    public record PriceChange(Outcome outcome, List<PriceRow> rows, String message) {
    }

    /** seat-service's answer for one event. */
    @Data
    @NoArgsConstructor
    public static class PriceRow {
        private UUID eventId;
        private UUID categoryId;
        /** UPDATED, UNCHANGED, NO_SUCH_CATEGORY or REFUSED. */
        private String outcome;
        private java.math.BigDecimal oldPrice;
        private String message;
    }

    @Data
    @NoArgsConstructor
    public static class PriceRows {
        private List<PriceRow> results;
    }

    /**
     * Sets the named category's full price on each event
     * ({@code POST /seat-categories/internal/price-by-name}, TIC-71). Never
     * retried here and never called with a transaction open. DONE carries a row
     * per event; REFUSED carries seat-service's message (a field it refused);
     * UNAVAILABLE means no readable answer, and some events may have been
     * changed: asking again is safe, since an unchanged price is left alone.
     */
    public PriceChange setPriceByName(List<UUID> eventIds, String categoryName, java.math.BigDecimal price,
                                      String changedBy) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventIds", eventIds.stream().map(UUID::toString).toList());
        body.put("categoryName", categoryName);
        body.put("price", price);
        body.put("changedBy", changedBy);
        try {
            ResponseEntity<ApiResult<PriceRows>> response = restTemplate.exchange(
                    seatServiceBaseUrl + "/seat-categories/internal/price-by-name", HttpMethod.POST,
                    new HttpEntity<>(body, headers), new ParameterizedTypeReference<>() { });
            ApiResult<PriceRows> envelope = response.getBody();
            PriceRows rows = envelope == null ? null : envelope.getData();
            if (rows == null || rows.getResults() == null) {
                log.warn("Price by name answered without results events={}", eventIds.size());
                return new PriceChange(Outcome.UNAVAILABLE, List.of(), null);
            }
            return new PriceChange(Outcome.DONE, List.copyOf(rows.getResults()), null);
        } catch (HttpClientErrorException e) {
            String message = null;
            try {
                ApiResult<?> error = e.getResponseBodyAs(ApiResult.class);
                message = error == null ? null : error.getMessage();
            } catch (RuntimeException unreadable) {
                // The status is enough; the message is a courtesy.
            }
            log.warn("Price by name refused events={} status={}", eventIds.size(), e.getStatusCode().value());
            return new PriceChange(Outcome.REFUSED, List.of(), message);
        } catch (RestClientException e) {
            log.warn("Price by name failed events={} cause={}", eventIds.size(), e.toString());
            return new PriceChange(Outcome.UNAVAILABLE, List.of(), null);
        }
    }

    /** seat-service's {@code CopiedLayout}, the fields read here. */
    @Data
    @NoArgsConstructor
    public static class CopiedLayout {
        private boolean copied;
        private List<CopiedCategory> categories;
    }

    @Data
    @NoArgsConstructor
    public static class CopiedCategory {
        private UUID sourceCategoryId;
        private UUID categoryId;
        private String name;
    }
}
