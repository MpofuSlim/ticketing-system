package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One add-on's sales on paid bookings in the window (V33). {@code name} is the
 * latest it sold under; {@code revenue} the sum of the prices it sold at.
 */
@Schema(name = "AddOnSales")
public record AddOnSalesDTO(
        @Schema(example = "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01") UUID addOnId,
        @Schema(example = "Parking pass") String name,
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID eventId,
        @Schema(example = "VIP") String categoryName,
        @Schema(example = "12") long sold,
        @Schema(example = "60.00") BigDecimal revenue) {
}
