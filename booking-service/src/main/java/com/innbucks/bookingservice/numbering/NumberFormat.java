package com.innbucks.bookingservice.numbering;

/**
 * How an assigned number reads (V34): the prefix, then the value padded with
 * zeros in front to {@code padding} digits. A value wider than the padding is
 * printed in full, never cut: prefix "T", padding 3 gives T001 … T999, T1000.
 */
public final class NumberFormat {

    /** The label a series gets when the organizer gives none. */
    public static final String DEFAULT_LABEL = "Number";

    private NumberFormat() {
    }

    public static String render(String prefix, long value, int padding) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder(prefix == null ? "" : prefix);
        for (int k = digits.length(); k < padding; k++) {
            out.append('0');
        }
        return out.append(digits).toString();
    }
}
