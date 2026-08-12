package innbucks.paymentservice.client;

import java.util.regex.Pattern;

/**
 * Classifier for COPYandPAY {@code result.code} values.
 *
 * <p>OPPWA encodes outcome in a dotted numeric code and documents the
 * groupings as regexes rather than an enumerable list — new codes appear
 * within existing families, so matching the family is the forward-compatible
 * read. Patterns transcribed from the ZimSwitch/OPPWA result-code reference.
 *
 * <p>The important asymmetry: <b>SUCCESS and REJECTED are conclusions;
 * PENDING is not.</b> A pending code means the gateway still has an open
 * session that will resolve (or time out) within ~30 minutes, so a pending row
 * is polled, never auto-failed. Anything we cannot classify is treated as
 * PENDING rather than FAILED for the same reason the InnBucks rail leaves
 * UNKNOWN rows alone: a blocked slot is recoverable, a double charge is not.
 */
public enum ZimswitchResultCode {

    /** Payment succeeded — money moved. */
    SUCCESS,

    /**
     * Succeeded, but the gateway wants a human to look at it (fraud
     * screening). Money moved, so the ledger treats it as success; the flag
     * exists so ops can see it.
     */
    SUCCESS_MANUAL_REVIEW,

    /** Still open upstream. Poll again; never auto-fail. */
    PENDING,

    /** Declined, rejected, or invalid. No money moved. */
    REJECTED;

    /** Transaction succeeded. */
    private static final Pattern SUCCESSFUL =
            Pattern.compile("^(000\\.000\\.|000\\.100\\.1|000\\.[36])");

    /** Succeeded but flagged for manual review (fraud screening). */
    private static final Pattern MANUAL_REVIEW =
            Pattern.compile("^(000\\.400\\.0[^3]|000\\.400\\.100)");

    /** Open session in the background — resolves or times out within ~30 min. */
    private static final Pattern PENDING_PATTERN =
            Pattern.compile("^(000\\.200)");

    /**
     * Result code returned by a successful <i>prepare-checkout</i> call. It is
     * NOT a payment outcome — feeding it to {@link #classify} would read as
     * PENDING and silently mean "checkout created" forever.
     */
    public static final String CHECKOUT_CREATED = "000.200.100";

    /**
     * Classify a payment-status {@code result.code}.
     *
     * <p>A null/blank code is PENDING, not REJECTED: it means we failed to
     * read an outcome, which is exactly the case where guessing costs money.
     */
    public static ZimswitchResultCode classify(String resultCode) {
        if (resultCode == null || resultCode.isBlank()) {
            return PENDING;
        }
        String code = resultCode.trim();
        if (SUCCESSFUL.matcher(code).find()) {
            return SUCCESS;
        }
        if (MANUAL_REVIEW.matcher(code).find()) {
            return SUCCESS_MANUAL_REVIEW;
        }
        if (PENDING_PATTERN.matcher(code).find()) {
            return PENDING;
        }
        return REJECTED;
    }

    /** True when money moved (either success flavour). */
    public boolean isPaid() {
        return this == SUCCESS || this == SUCCESS_MANUAL_REVIEW;
    }

    /** True when the outcome is settled — safe to write a terminal state. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
