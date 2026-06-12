package innbucks.paymentservice.client;

/**
 * Outcome of {@code POST /api/code/inquiry} (InnBucks
 * Merchant API). Status vocabulary per the doc; the doc describes BOTH
 * {@code Claimed} and {@code Paid} as "finalised by the customer", so the
 * poller treats either as money-received for a PAYMENT-type code. Anything
 * unrecognisable maps to {@link Status#UNKNOWN} and the row is left alone —
 * expiring a code the customer may have paid is the double-charge path.
 *
 * @param status      classified status
 * @param rawStatus   the status string exactly as the platform sent it
 * @param responseMsg upstream message (failure reason on ERROR)
 */
public record CodeStatusResult(Status status, String rawStatus, String responseMsg) {

    public enum Status {
        /** Code generated, not yet approved by the customer. */
        NEW,
        /** Finalised by the customer (doc wording — see class javadoc). */
        CLAIMED,
        /** Finalised by the customer — paid. */
        PAID,
        /** Code validity period elapsed unpaid. */
        EXPIRED,
        /** Transaction window exceeded. */
        TIMED_OUT,
        /** Platform answered with a non-zero responseCode. */
        ERROR,
        /** 2xx envelope but an unrecognisable status value. */
        UNKNOWN
    }

    /** Customer money landed (Paid, or Claimed per the doc's definition). */
    public boolean isPaid() {
        return status == Status.PAID || status == Status.CLAIMED;
    }
}
