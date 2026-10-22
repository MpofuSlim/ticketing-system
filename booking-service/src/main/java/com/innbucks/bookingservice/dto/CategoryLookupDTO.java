package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Capacity + pricing of a seat category, fetched from seat-service's
 * {@code GET /seat-categories/{id}}. Mirrors seat-service's
 * {@code CategoryCapacityDTO} wire shape. Used by booking-service to:
 * <ul>
 *   <li>seed its per-category inventory counter from {@code totalSeats},</li>
 *   <li>price each ticket from {@code price} and {@code priceTiers}
 *       ({@code PriceTierAllocator}),</li>
 *   <li>validate the category belongs to the event being booked
 *       ({@code eventId}).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryLookupDTO {
    private UUID seatCategoryId;
    private UUID eventId;
    private String name;
    /** The full price, charged once every tier has closed. */
    private BigDecimal price;
    /** The category's price tiers in the order they apply; null or empty when it has none. */
    private List<PriceTier> priceTiers;
    private Integer totalSeats;
    private Integer availableSeats;

    /**
     * One tier's terms. {@code endsAt} is a string because seat-service
     * renders it as {@code Z} for a service caller but could render the market
     * offset; {@code PriceTierAllocator} reads either.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceTier {
        private UUID id;
        private String name;
        private BigDecimal price;
        private String endsAt;
        private Integer quantity;
    }
}
