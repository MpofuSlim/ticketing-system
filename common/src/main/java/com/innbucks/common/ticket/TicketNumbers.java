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

    /**
     * Anything shaped like a current number inside free text, case and alphabet
     * ignored on purpose: a log redaction errs towards hiding, so a near-miss
     * (an O typed for a 0) is hidden too. The look-arounds keep it off longer
     * runs of letters and digits.
     */
    private static final Pattern CURRENT_IN_TEXT = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{8}-[0-9A-Za-z]{5}-[0-9A-Za-z]{5}(?![0-9A-Za-z])");

    /** Anything shaped like a legacy number inside free text. */
    private static final Pattern LEGACY_IN_TEXT = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{8}-\\d{5}[A-Za-z](?![0-9A-Za-z])");

    private TicketNumbers() {
    }

    /**
     * A ticket number as a log line may carry it: the issue date and the last
     * few symbols, e.g. {@code 20261013-*****-**Z2B} (current) or
     * {@code 20260419-****1X} (legacy, which has far less randomness, so only
     * two symbols show). Enough to tell tickets apart in an incident; not
     * enough to get through a gate. Logs are read by more people, kept longer
     * and copied further than the database, and a full number admits whoever
     * holds it.
     *
     * <p>A value that is not a ticket number, e.g. whatever a scanner read on a
     * scan miss, is never echoed: it may be a real number with one symbol
     * wrong, or text crafted to forge a log line. Only its length is given.
     * Never throws.
     */
    public static String forLog(String value) {
        if (value == null || value.isBlank()) {
            return "(none)";
        }
        if (isTicketNumber(value)) {
            return masked(value);
        }
        Optional<String> issued = canonical(value);
        if (issued.isEmpty()) {
            return "(not a ticket number, " + value.length() + " chars)";
        }
        return masked(issued.get());
    }

    /**
     * {@code text} with every ticket-number-shaped run masked as
     * {@link #forLog} would mask it: for request paths
     * ({@code /bookings/{id}/tickets/{ticketNumber}/qr.png}) and for an
     * upstream's error body, which may echo what we sent. Null stays null.
     * Never throws.
     */
    public static String redactIn(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = CURRENT_IN_TEXT.matcher(text).replaceAll(m -> Matcher.quoteReplacement(maskCurrent(m.group())));
        return LEGACY_IN_TEXT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(maskLegacy(m.group())));
    }

    private static String masked(String issued) {
        return isLegacy(issued) ? maskLegacy(issued) : maskCurrent(issued);
    }

    /** {@code yyyyMMdd-XXXXX-XXXXX} to {@code yyyyMMdd-*****-**XXX}. */
    private static String maskCurrent(String value) {
        return value.substring(0, 9) + "*****-**" + value.substring(17);
    }

    /** {@code yyyyMMdd-DDDDDL} to {@code yyyyMMdd-****DL}. */
    private static String maskLegacy(String value) {
        return value.substring(0, 9) + "****" + value.substring(13);
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
