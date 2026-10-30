package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One row of a revenue summary's split by how the bookings were paid, from
 * {@code bookings.payment_rail} (V29) through {@code PaymentMethods}. The rows
 * of one summary add up to its {@code netRevenue}, {@code confirmedBookings}
 * and {@code ticketsSold}.
 */
@Schema(name = "OrganizerPaymentMethodRevenue",
        description = "Bookings, tickets and money for one payment method. The rows add up to the summary's "
                + "netRevenue, confirmedBookings and ticketsSold.")
public record PaymentMethodRevenueDTO(
        @Schema(example = "ECOCASH", description = "INNBUCKS, ECOCASH or CARD (a ZimSwitch-enabled bank card); "
                + "a rail added later shows by its own name; NOT_RECORDED when the booking has no method on record "
                + "(made by hand, or confirmed before methods were recorded and not backfilled).")
        String method,
        @Schema(example = "EcoCash", description = "Ready to print: \"InnBucks\", \"EcoCash\", "
                + "\"Bank card (ZimSwitch)\" or \"Not recorded\".")
        String label,
        @Schema(example = "1") long bookings,
        @Schema(example = "3") long ticketsSold,
        @Schema(example = "300.00") BigDecimal amount
) {
    /** The method code for confirmed bookings with no payment method on record. */
    public static final String NOT_RECORDED = "NOT_RECORDED";

    /** The label printed for {@link #NOT_RECORDED}. */
    public static final String NOT_RECORDED_LABEL = "Not recorded";
}
