package com.innbucks.eventservice.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class SeatCategoryServiceResponseDTO {
    private UUID eventId;
    private String name;
    private String description;
    /** The category's full price. */
    private BigDecimal price;
    /** The price tiers in the order they apply, with seat-service's live counts; see {@link PriceTier}. */
    private List<PriceTier> priceTiers;
    /**
     * Seats left in the category, live when seat-service answered. Cached with
     * the layout and read for display only: it decides whether a category can
     * be an event's "from" price ({@link EventPriceFromDTO}), never a sale.
     */
    private Integer availableSeats;
    private List<SeatCategorySectionDTO> sections;

    /**
     * One seat-service price tier. Its {@code status} and the category's
     * {@code currentPrice} are deliberately NOT read: this answer is cached, so
     * event-service works out which tier applies at each read from the terms
     * and the sold count. {@code endsAt} is a string because seat-service
     * renders it at the market offset or as {@code Z} depending on the caller,
     * and an unreadable one must drop the tier, not the whole category list.
     */
    @Data
    public static class PriceTier {
        private UUID id;
        private String name;
        private BigDecimal price;
        private String startsAt;
        private String endsAt;
        private Integer quantity;
        private Integer maxPerCustomer;
        /** Absent when seat-service could not ask booking-service. */
        private Long sold;
    }
}
