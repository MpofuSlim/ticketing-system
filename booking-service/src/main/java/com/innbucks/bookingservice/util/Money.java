package com.innbucks.bookingservice.util;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * An amount as a buyer reads it: {@code $0.40} in a US-dollar cell, and the
 * ISO code in front of anything else ({@code ZWG 12.50}), because "$" alone
 * would claim dollars for a currency that is not.
 *
 * <p>For what we SEND people (the WhatsApp ticket caption, the receipt email,
 * the ticket page). API responses keep their bare decimals: clients format
 * those themselves, and a symbol in a number field would break them.
 *
 * <p>Plain ASCII on purpose: the notification API has refused typographic
 * characters, and a WhatsApp template variable must stay one simple line.
 */
public final class Money {

    private Money() {
    }

    /** {@code $0.40}, {@code ZWG 12.50}, or the bare amount when no currency is set. */
    public static String display(BigDecimal amount, String currency) {
        if (amount == null) {
            return "";
        }
        String plain = amount.toPlainString();
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            return plain;
        }
        if ("USD".equals(code)) {
            return amount.signum() < 0 ? "-$" + amount.negate().toPlainString() : "$" + plain;
        }
        return code + " " + plain;
    }
}
