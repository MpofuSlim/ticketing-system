package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the per-event revenue breakdown — a single event the organizer
 * owns, with its CONFIRMED totals and any refunds. Same revenue semantics as
 * {@link RevenueSummaryDTO}.
 */
@Schema(name = "OrganizerEventRevenue",
        description = "Revenue for a single event owned by the calling organizer.")
public record EventRevenueDTO(
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID eventId,
        @Schema(example = "128") long confirmedBookings,
        @Schema(example = "342") long ticketsSold,
        @Schema(description = "Collected before refunds (confirmed + reversed).", example = "34200.00")
        BigDecimal grossRevenue,
        @Schema(example = "300.00") BigDecimal refundedAmount,
        @Schema(description = "grossRevenue - refundedAmount.", example = "33900.00") BigDecimal netRevenue
) { }
