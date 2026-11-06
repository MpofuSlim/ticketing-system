package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A sales agent's own sales (V36, {@code GET /bookings/box-office/my-sales}):
 * every sale the signed-in account made as an agent in the window, newest
 * first, what it adds up to, and the cash the organizers recorded receiving.
 * Never another agent's sales and never a buyer's full contact details.
 */
@Schema(name = "AgentSalesReport", description = "Your sales as a sales agent")
public record AgentSalesReportDTO(
        @Schema(description = "First day included; null = from your first sale", nullable = true,
                example = "2026-11-01")
        LocalDate from,
        @Schema(description = "Last day included; null = up to now", nullable = true, example = "2026-11-30")
        LocalDate to,
        @Schema(description = "The event the report is filtered to; null = every event", nullable = true)
        UUID eventId,
        @Schema(description = "Every sale made or voided in the window, newest first") List<Entry> entries,
        Totals totals,
        @Schema(description = "Cash the organisers recorded receiving from you in the window, oldest first")
        List<AgentHandoverDTO> handovers) {

    @Schema(name = "AgentSale", description = "One sale")
    public record Entry(
            UUID bookingId,
            @Schema(example = "INN-20261114-4F2A9C") String confirmationNumber,
            UUID eventId,
            @Schema(description = "When the sale was made") LocalDateTime createdAt,
            @Schema(example = "Rufaro Moyo") String customerName,
            @Schema(description = "The buyer's phone, last four digits only", example = "****4567")
            String customerPhone,
            @Schema(example = "2") int tickets,
            @Schema(description = "What the buyer paid or owes: tickets and add-ons", example = "300.00")
            BigDecimal amount,
            @Schema(description = "CASH, or how an online sale was paid (INNBUCKS, ECOCASH, CARD); null while "
                    + "an online sale is unpaid", nullable = true, example = "CASH")
            String paymentMethod,
            @Schema(nullable = true, example = "Cash (box office)") String paymentMethodLabel,
            @Schema(description = "CONFIRMED (sold), PENDING (an online sale awaiting payment), VOIDED (a cash "
                    + "sale the organiser voided) or CANCELLED (an online sale nobody paid for)",
                    allowableValues = {"CONFIRMED", "PENDING", "VOIDED", "CANCELLED"}, example = "CONFIRMED")
            String status,
            @Schema(description = "Your commission percentage on this sale, as it was when you sold",
                    example = "5.00")
            BigDecimal commissionPercent,
            @Schema(description = "Your commission on this sale; null unless CONFIRMED", nullable = true,
                    example = "15.00")
            BigDecimal commission) {
    }

    @Schema(name = "AgentSalesTotals")
    public record Totals(
            @Schema(description = "Tickets in CONFIRMED sales made in the window", example = "6") int ticketsSold,
            @Schema(description = "Cash taken: CASH sales made in the window", example = "900.00")
            BigDecimal cashTaken,
            @Schema(description = "Cash handed back: CASH sales voided in the window", example = "150.00")
            BigDecimal cashVoided,
            @Schema(description = "ONLINE sales paid, made in the window (the money went online, not to you)",
                    example = "300.00")
            BigDecimal onlineSales,
            @Schema(description = "Commission on CONFIRMED sales made in the window", example = "52.50")
            BigDecimal commissionEarned,
            @Schema(description = "Cash the organisers recorded receiving from you in the window",
                    example = "500.00")
            BigDecimal handedOver,
            @Schema(description = "cashTaken - cashVoided - handedOver: cash still to hand over. Negative = "
                    + "more was handed over than taken in this window", example = "250.00")
            BigDecimal cashOwed,
            @Schema(example = "USD") String currency) {
    }
}
