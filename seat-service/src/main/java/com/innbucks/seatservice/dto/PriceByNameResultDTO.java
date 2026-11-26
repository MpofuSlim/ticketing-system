package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What {@code POST /seat-categories/internal/price-by-name} did, event by event (TIC-71). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PriceByNameResult")
public class PriceByNameResultDTO {

    /** One event's outcome. */
    public enum Outcome {
        /** The price moved, and the change is in the category's price history. */
        UPDATED,
        /** The category already had that price; nothing recorded. */
        UNCHANGED,
        /** The event has no live category of that name. */
        NO_SUCH_CATEGORY,
        /** The price rules refused it (a price tier still open at or above it); {@code message} says why. */
        REFUSED
    }

    private List<Row> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "PriceByNameRow")
    public static class Row {
        private UUID eventId;
        @Schema(nullable = true)
        private UUID categoryId;
        private Outcome outcome;
        @Schema(nullable = true, description = "The price before, when there was a category.")
        private BigDecimal oldPrice;
        @Schema(nullable = true, description = "Why it was refused.")
        private String message;
    }
}
