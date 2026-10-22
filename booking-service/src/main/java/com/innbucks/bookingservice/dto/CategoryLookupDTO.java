package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Capacity + pricing of a seat category, fetched from seat-service's
 * {@code GET /seat-categories/{id}}. Mirrors seat-service's
 * {@code CategoryCapacityDTO} wire shape. Used by booking-service to:
 * <ul>
 *   <li>seed its per-category inventory counter from {@code totalSeats},</li>
 *   <li>compute the booking total from {@link #priceNow()},</li>
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
    /** The category's full price. */
    private BigDecimal price;

    /**
     * The price in force when seat-service answered: the early-bird price
     * while one runs, else {@code price}. Null from a seat-service that
     * predates early birds.
     */
    private BigDecimal currentPrice;

    private Integer totalSeats;
    private Integer availableSeats;

    /**
     * What one ticket of this category is charged: {@code currentPrice},
     * falling back to {@code price}. Not a bean getter, so it never becomes a
     * JSON property.
     */
    public BigDecimal priceNow() {
        return currentPrice != null ? currentPrice : price;
    }
}
