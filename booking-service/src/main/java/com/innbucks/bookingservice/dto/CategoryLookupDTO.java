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
 *   <li>compute the booking total from {@code price},</li>
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
    private BigDecimal price;
    private Integer totalSeats;
    private Integer availableSeats;
}
