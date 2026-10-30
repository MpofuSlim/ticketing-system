package com.innbucks.bookingservice.util;

import java.util.regex.Pattern;

/**
 * The payment method an organizer sees for a booking, from the rail
 * payment-service recorded at confirm ({@code bookings.payment_rail}, V29).
 *
 * <ul>
 *   <li>{@code INNBUCKS_CODE} → {@code INNBUCKS}, "InnBucks"</li>
 *   <li>{@code ECOCASH} → {@code ECOCASH}, "EcoCash"</li>
 *   <li>{@code ZIMSWITCH_CARD} → {@code CARD}, "Bank card (ZimSwitch)". Not
 *       "Visa/Mastercard": ZimSwitch takes ZimSwitch-enabled cards only on our
 *       channel ({@code docs/api/zimswitch-copyandpay.md}).</li>
 * </ul>
 * A rail this class does not know yet is shown by its own name, so a rail
 * payment-service adds later is never hidden. No rail = null (not recorded).
 */
public final class PaymentMethods {

    /** What {@link #cleanRail} keeps: a plain upper-case name, at most the column's 32. */
    private static final Pattern RAIL = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private PaymentMethods() {
    }

    /** The rail to store, or null for a blank or malformed value (never refused: the payment has landed). */
    public static String cleanRail(String rail) {
        if (rail == null) return null;
        String trimmed = rail.strip();
        return RAIL.matcher(trimmed).matches() ? trimmed : null;
    }

    /** The method code for a stored rail: INNBUCKS, ECOCASH, CARD, the rail itself, or null. */
    public static String code(String rail) {
        if (rail == null) return null;
        return switch (rail) {
            case "INNBUCKS_CODE" -> "INNBUCKS";
            case "ECOCASH" -> "ECOCASH";
            case "ZIMSWITCH_CARD" -> "CARD";
            default -> rail;
        };
    }

    /** The words for a stored rail, ready to print, or null when none was recorded. */
    public static String label(String rail) {
        if (rail == null) return null;
        return switch (rail) {
            case "INNBUCKS_CODE" -> "InnBucks";
            case "ECOCASH" -> "EcoCash";
            case "ZIMSWITCH_CARD" -> "Bank card (ZimSwitch)";
            default -> rail;
        };
    }
}
