package com.innbucks.seatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Capacity + pricing projection of a seat category, returned by
 * {@code GET /seat-categories/{id}}. booking-service calls this to learn a
 * category's {@code totalSeats} (to seed its own per-category inventory
 * counter), {@code price} (to compute the booking total), and {@code eventId}
 * (to validate the category belongs to the event being booked) — without
 * picking an individual seat.
 *
 * <p>Part of the GA inventory model: tickets in a category are fungible, so
 * the meaningful unit is the category's capacity, not a specific seat row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryCapacityDTO {
    private UUID seatCategoryId;
    private UUID eventId;
    private String name;
    private BigDecimal price;
    private Integer totalSeats;
    private Integer availableSeats;
}
