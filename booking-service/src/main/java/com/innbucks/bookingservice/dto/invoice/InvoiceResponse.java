package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A platform commission invoice issued to an event organizer.
 *
 * <p>{@code totalAmount = commissionAmount + taxAmount} is what the organizer
 * owes. {@code commissionAmount = round2(grossSales * commissionRate%)} and
 * {@code taxAmount = round2(commissionAmount * taxRate%)}, both snapshotted at
 * generation. {@code lineItems} break the commission down per event.
 */
@Schema(name = "Invoice", description = "Platform commission invoice for an event organizer's period.")
public record InvoiceResponse(
        @Schema(example = "b4c0d2e3-9f1a-4c5d-8e6f-1a2b3c4d5e6f") UUID id,
        @Schema(description = "Human-readable invoice number.", example = "INV-2026-000042") String invoiceNumber,
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") UUID organizerUuid,
        @Schema(description = "First day of the billing period (inclusive).", example = "2026-05-01")
        LocalDate periodStart,
        @Schema(description = "Last day of the billing period (inclusive).", example = "2026-05-31")
        LocalDate periodEnd,
        @Schema(description = "ISSUED, PAID, OVERDUE or CANCELLED.", example = "ISSUED") String status,
        @Schema(example = "USD") String currency,
        @Schema(description = "CONFIRMED bookings across the period.", example = "128") long confirmedBookings,
        @Schema(description = "Tickets sold across the period.", example = "342") long ticketsSold,
        @Schema(description = "Net confirmed ticket revenue the commission is charged on.", example = "34200.00")
        BigDecimal grossSales,
        @Schema(description = "Commission rate applied (percent).", example = "10.0000") BigDecimal commissionRate,
        @Schema(description = "Commission (fee) = invoice subtotal before tax.", example = "3420.00")
        BigDecimal commissionAmount,
        @Schema(description = "VAT rate applied to the commission (percent).", example = "15.0000")
        BigDecimal taxRate,
        @Schema(description = "VAT charged on the commission.", example = "513.00") BigDecimal taxAmount,
        @Schema(description = "Total owed = commission + tax.", example = "3933.00") BigDecimal totalAmount,
        @Schema(example = "2026-06-01T01:30:00") LocalDateTime issuedAt,
        @Schema(example = "2026-06-15T01:30:00") LocalDateTime dueAt,
        @Schema(description = "When marked paid; null until then.", nullable = true) LocalDateTime paidAt,
        @Schema(description = "When cancelled; null unless cancelled.", nullable = true) LocalDateTime cancelledAt,
        @Schema(description = "Per-event commission breakdown.") List<InvoiceLineItemResponse> lineItems
) {
}
