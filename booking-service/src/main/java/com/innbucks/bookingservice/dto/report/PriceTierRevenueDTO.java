package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tickets sold and revenue at one price tier of a ticket class, from each
 * ticket's own {@code priceTierId} / {@code priceTierName} / {@code priceAtBooking}
 * (V26). The full-price row has a null {@code priceTierId}.
 */
@Schema(name = "OrganizerPriceTierRevenue",
        description = "Tickets sold and revenue at one price tier (or the full price) of a ticket class.")
public record PriceTierRevenueDTO(
        @Schema(example = "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f", nullable = true,
                description = "The seat-service tier id; null for tickets sold at the full price.")
        UUID priceTierId,
        @Schema(example = "Early bird",
                description = "The tier's name when its tickets were sold, or \"Full price\".")
        String name,
        @Schema(example = "100") long ticketsSold,
        @Schema(example = "10000.00") BigDecimal revenue
) {
    /** The name of the row for tickets sold at no tier. */
    public static final String FULL_PRICE = "Full price";
}
