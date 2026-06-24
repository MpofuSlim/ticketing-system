package com.innbucks.bookingservice.dto.invoice;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Platform-wide invoice dashboard totals. {@code outstanding*} covers unpaid
 * money still owed (ISSUED + OVERDUE).
 */
@Schema(name = "InvoiceSummary", description = "Aggregate invoice counts and amounts across all organizers.")
public record InvoiceSummaryResponse(
        @Schema(example = "210") long totalInvoices,
        @Schema(description = "Sum of every invoice total ever issued.", example = "82540.00") BigDecimal totalBilled,
        @Schema(example = "12") long issuedCount,
        @Schema(example = "3") long overdueCount,
        @Schema(example = "190") long paidCount,
        @Schema(example = "5") long cancelledCount,
        @Schema(description = "Unpaid money owed = ISSUED + OVERDUE totals.", example = "9320.00")
        BigDecimal outstandingAmount,
        @Schema(description = "Money settled (PAID totals).", example = "72100.00") BigDecimal paidAmount,
        @Schema(example = "USD") String currency
) {
}
