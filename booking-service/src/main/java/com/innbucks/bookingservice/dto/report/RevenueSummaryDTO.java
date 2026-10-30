package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Period revenue summary for an EVENT_ORGANIZER, scoped to the events they own.
 *
 * <p>Revenue is recognised from CONFIRMED bookings — a booking confirms only
 * after payment lands, so {@code netRevenue} is money collected. A booking
 * that was CONFIRMED and later reversed by an admin (refund) is counted in
 * {@code grossRevenue} and {@code refundedAmount}; {@code netRevenue =
 * grossRevenue - refundedAmount}. PENDING holds that simply expired were never
 * paid and are excluded entirely.
 *
 * <p>All figures are keyed on the booking's {@code createdAt} (when the sale
 * originated), in UTC. {@code byPaymentMethod} splits {@code netRevenue} by
 * the rail that paid each booking (V29).
 */
@Schema(name = "OrganizerRevenueSummary",
        description = "Period revenue summary for the calling organizer's events.")
public record RevenueSummaryDTO(
        @Schema(example = "2026-05-01") LocalDate from,
        @Schema(example = "2026-05-31") LocalDate to,
        @Schema(description = "Echoes the eventId filter; null when the summary spans all the organizer's events.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", nullable = true) UUID eventId,
        @Schema(description = "Count of CONFIRMED bookings in the period.", example = "128") long confirmedBookings,
        @Schema(description = "Total tickets across those CONFIRMED bookings.", example = "342") long ticketsSold,
        @Schema(description = "Money collected before refunds (confirmed + later-reversed).", example = "34200.00")
        BigDecimal grossRevenue,
        @Schema(description = "Cash portion of confirmed bookings.", example = "33950.00") BigDecimal cashCollected,
        @Schema(description = "Loyalty points applied against confirmed bookings.", example = "2500.00")
        BigDecimal pointsRedeemed,
        @Schema(description = "Count of bookings that were paid then reversed (refunds).", example = "3")
        long refundedBookings,
        @Schema(description = "Total value of those reversals.", example = "300.00") BigDecimal refundedAmount,
        @Schema(description = "Net revenue kept = gross - refunded = sum of currently-CONFIRMED bookings.",
                example = "33900.00") BigDecimal netRevenue,
        @Schema(description = "netRevenue / confirmedBookings (0 when none).", example = "264.84")
        BigDecimal averageOrderValue,
        @Schema(example = "USD") String currency,
        @Schema(description = "netRevenue split by how the bookings were paid, largest amount first. The rows add "
                + "up to netRevenue, confirmedBookings and ticketsSold. Empty when there are no confirmed bookings.")
        List<PaymentMethodRevenueDTO> byPaymentMethod
) {
    /** A summary with no payment-method split (kept for callers built before it existed). */
    public RevenueSummaryDTO(LocalDate from, LocalDate to, UUID eventId, long confirmedBookings, long ticketsSold,
                             BigDecimal grossRevenue, BigDecimal cashCollected, BigDecimal pointsRedeemed,
                             long refundedBookings, BigDecimal refundedAmount, BigDecimal netRevenue,
                             BigDecimal averageOrderValue, String currency) {
        this(from, to, eventId, confirmedBookings, ticketsSold, grossRevenue, cashCollected, pointsRedeemed,
                refundedBookings, refundedAmount, netRevenue, averageOrderValue, currency, List.of());
    }
}
