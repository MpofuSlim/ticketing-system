package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the per-ticket-class (category) breakdown for a single event:
 * how many tickets of this class sold and the revenue they brought in.
 *
 * <p>Computed at the item level over CONFIRMED bookings — each booking item is
 * one ticket carrying its own {@code priceAtBooking}, so summing item prices
 * is exact (no booking-level fan-out).
 */
@Schema(name = "OrganizerCategoryRevenue",
        description = "Tickets sold and revenue for one ticket class within an event.")
public record CategoryRevenueDTO(
        @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11") UUID categoryId,
        @Schema(example = "VIP") String categoryName,
        @Schema(example = "120") long ticketsSold,
        @Schema(example = "12000.00") BigDecimal revenue
) { }
