package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** Per-event breakdown row of an invoice. */
@Schema(name = "InvoiceLineItem", description = "Commission breakdown for one event within an invoice.")
public record InvoiceLineItemResponse(
        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID eventId,
        @Schema(description = "CONFIRMED bookings for this event in the period.", example = "120")
        long confirmedBookings,
        @Schema(description = "Tickets sold for this event in the period.", example = "320") long ticketsSold,
        @Schema(description = "Net confirmed ticket revenue for this event.", example = "32000.00")
        BigDecimal grossSales,
        @Schema(description = "Commission charged on this event's revenue.", example = "3200.00")
        BigDecimal commissionAmount
) {
}
