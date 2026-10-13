package com.innbucks.common.ticket;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ONE definition of what a booking ticket number looks like, for every
 * service that has to recognise one. booking-service mints them
 * ({@code BookingReferences}); user-service's support search classifies a
 * typed one. A ticket number is the QR payload a gate scanner validates, so it
 * is an entry credential, not a cosmetic id.
 *
 * <p>Two shapes, and BOTH are valid forever:
 * <ul>
 *   <li><b>Current</b> — {@code yyyyMMdd-XXXXX-XXXXX}, e.g.
 *       {@code 20261013-7KQ4M-9XZ2B}: the UTC issue date, then ten symbols of
 *       {@link #ALPHABET} (50 random bits), at least one of them a letter.</li>
 *   <li><b>Legacy</b> — {@code yyyyMMdd-DDDDDL}, e.g. {@code 20260419-48291X}:
 *       five digits and one letter (2.6M values a day). Every ticket issued
 *       before the current shape is a row and a printed QR; nothing may start
 *       refusing it.</li>
 * </ul>
 *
 * <p>The gate never parses a ticket number — it looks the scanned value up
 * exactly as issued. Recognition is for places that have to tell a ticket
 * number apart from something else a person typed.
 */
public final class TicketNumbers {

    /**
     * Crockford base32, upper case: digits and letters minus I, L, O and U,
     * which read as 1, 1, 0 and V. 32 symbols, so one symbol is exactly five
     * random bits.
     */
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** Symbols per dash-separated group of a current-shape number. */
    public static final int GROUP_LENGTH = 5;

    /** Random symbols in a current-shape number (two groups). */
    public static final int RANDOM_SYMBOLS = 2 * GROUP_LENGTH;

    private static final String SYMBOL = "[0-9A-HJKMNP-TV-Z]";

    /** The current shape, exactly as issued (upper case, ASCII dashes). */
    public static final Pattern CURRENT = Pattern.compile(
            "^\\d{8}-(?=.*[A-Z])" + SYMBOL + "{5}-" + SYMBOL + "{5}$");

    /** The legacy shape, exactly as issued. */
    public static final Pattern LEGACY = Pattern.compile("^\\d{8}-\\d{5}[A-Z]$");

    /**
     * The date group a TYPED number must carry: a plausible {@code 20yyMMdd}.
     * Every number ever issued starts with its UTC issue date, so this refuses
     * no ticket. It is what keeps a card number with a letter or two typed after
     * it out of {@link #canonical} once the dashes are optional:
     * {@code 4111 1111 1111 1111 AB} would otherwise read as
     * {@code 41111111-11111-111AB}, a "ticket" whose value is the card's digits.
     * No live card scheme's numbers start with 20 (Mastercard's 2-series is
     * 2221–2720, Mir's 2200–2204). {@link #CURRENT} and {@link #LEGACY} keep
     * {@code \d{8}}: they describe the issued shapes, and nothing may start
     * refusing one.
     */
    private static final String TYPED_DATE = "20\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])";

    /** Either shape with each dash optional, applied after {@link #canonical} has tidied the input. */
    private static final Pattern RELAXED = Pattern.compile(
            "^(" + TYPED_DATE + ")-?(?:(\\d{5}[A-Z])|(" + SYMBOL + "{5})-?(" + SYMBOL + "{5}))$");

    /** Whitespace of every kind, the no-break spaces a paste carries included. */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]");

    /** Hyphen, the Unicode dash family and the minus sign: a paste from a document turns '-' into these. */
    private static final Pattern DASH_VARIANTS = Pattern.compile("[\\u2010-\\u2015\\u2212]");

    private TicketNumbers() {
    }

    /** True for a current-shape number exactly as issued. */
    public static boolean isCurrent(String value) {
        return value != null && CURRENT.matcher(value).matches();
    }

    /** True for a legacy-shape number exactly as issued. */
    public static boolean isLegacy(String value) {
        return value != null && LEGACY.matcher(value).matches();
    }

    /** True for a ticket number of either shape, exactly as issued. */
    public static boolean isTicketNumber(String value) {
        return isCurrent(value) || isLegacy(value);
    }

    /**
     * The issued form of a ticket number a PERSON typed or pasted, or empty
     * when it is not one: spaces of every kind removed, ASCII letters
     * upper-cased (only ASCII — a full Unicode mapping turns {@code ß} into
     * {@code SS}), any dash variant read as {@code -}, and each dash optional,
     * so {@code 20261013 7kq4m 9xz2b} is {@code 20261013-7KQ4M-9XZ2B}. No
     * symbol is substituted for another: a current-shape number holding I, L,
     * O or U is not one, and a legacy number's final letter may be any letter.
     * The first eight digits must read as a {@code 20yyMMdd} date, which every
     * issued number does and a card number never does (see {@code TYPED_DATE}).
     */
    public static Optional<String> canonical(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String s = DASH_VARIANTS.matcher(SPACES.matcher(typed).replaceAll("")).replaceAll("-");
        s = asciiUpperCase(s);
        Matcher m = RELAXED.matcher(s);
        if (!m.matches()) {
            return Optional.empty();
        }
        String value = m.group(2) != null
                ? m.group(1) + "-" + m.group(2)
                : m.group(1) + "-" + m.group(3) + "-" + m.group(4);
        return isTicketNumber(value) ? Optional.of(value) : Optional.empty();
    }

    private static String asciiUpperCase(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'a' && chars[i] <= 'z') {
                chars[i] = (char) (chars[i] - ('a' - 'A'));
            }
        }
        return new String(chars);
    }
}
