package innbucks.paymentservice.service;

import java.util.Locale;
import java.util.UUID;

/**
 * Builds the InnBucks code-generation {@code reference} so the merchant bank
 * statement can be grouped and settled <b>per product/event</b> by prefix
 * filtering:
 *
 * <pre>  TKZ-&lt;TAG&gt;-&lt;12-hex unique suffix&gt;   e.g. TKZ-PINKRUN26-4F3A2B1C0D9E</pre>
 *
 * <ul>
 *   <li>{@code TKZ-} — fixed Ticketize marker, distinguishes our checkout
 *       money from anything else moving on the shared merchant account.</li>
 *   <li>{@code TAG} — the order gateway's settlement tag: for bookings the
 *       event's {@code settlementCode} (event-service, organizer-set or
 *       title-derived, [A-Z0-9]{3,12}) falling back to the first 8 hex of the
 *       eventId; for marketplace orders the fixed {@code MKT} tag.</li>
 *   <li>12-hex suffix — uniqueness. {@code payment_reference} is a DB-unique
 *       column AND InnBucks' duplicate-check key, so the reference can never be
 *       the tag alone; 48 random bits keep collisions out of reach at any
 *       realistic volume (~1% only past two million payments).</li>
 * </ul>
 *
 * <p>Max length: 4 + 12 + 1 + 12 = 29 chars — well inside our VARCHAR(64) and
 * the short-reference shapes the Merchant API doc samples show. With no tag
 * at all (defensive; bookings always carry an event) the legacy
 * {@code TKT-PMT-<uuid>} shape is kept so the reference is still unique.
 */
public final class SettlementReference {

    private static final String PREFIX = "TKZ-";
    private static final String LEGACY_PREFIX = "TKT-PMT-";
    private static final int TAG_MAX = 12;
    private static final int TAG_MIN = 3;
    private static final int SUFFIX_LENGTH = 12;

    private SettlementReference() {
    }

    /**
     * Booking-flavoured tag derivation: the sanitized event settlement code,
     * else the first 8 hex of the eventId (uppercased — a stable,
     * statement-safe tag), else null (the caller degrades to the legacy
     * reference shape).
     */
    public static String tagOf(String settlementCode, UUID eventId) {
        String tag = sanitizeTag(settlementCode);
        if (tag == null && eventId != null) {
            // UUID text always opens with 8 hex chars — a stable, statement-safe tag.
            tag = eventId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }
        return tag;
    }

    /**
     * Build the full unique reference from a settlement tag (re-sanitized —
     * the tag may come from another service). A null/unusable tag keeps the
     * legacy {@code TKT-PMT-<uuid>} shape so the reference is still unique.
     */
    public static String forTag(String tag) {
        String clean = sanitizeTag(tag);
        if (clean == null) {
            return LEGACY_PREFIX + UUID.randomUUID();
        }
        return PREFIX + clean + "-" + uniqueSuffix();
    }

    /** Convenience for the booking flow: {@code forTag(tagOf(code, eventId))}. */
    public static String build(String settlementCode, UUID eventId) {
        return forTag(tagOf(settlementCode, eventId));
    }

    /**
     * Defensive re-sanitization of an upstream-owned tag: keep letters and
     * digits, uppercase, cap at {@value #TAG_MAX}; anything that can't yield
     * {@value #TAG_MIN} usable chars counts as absent. Guarantees the
     * reference stays statement-safe even if the upstream value ever arrives
     * unnormalized.
     */
    static String sanitizeTag(String settlementCode) {
        if (settlementCode == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(TAG_MAX);
        for (int i = 0; i < settlementCode.length() && sb.length() < TAG_MAX; i++) {
            char c = settlementCode.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() >= TAG_MIN ? sb.toString() : null;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "")
                .substring(0, SUFFIX_LENGTH).toUpperCase(Locale.ROOT);
    }
}
