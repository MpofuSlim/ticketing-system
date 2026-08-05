package innbucks.paymentservice.order;

/**
 * Outcome of an {@link OrderGateway#confirm} call. Exists so the caller can
 * park a paid row {@code COMPLETED_UNCONFIRMED} only when it is GENUINELY
 * unconfirmed — a success (fresh or idempotent replay) promotes, everything
 * else parks for the confirm-retry sweep / operator queue. The money rule:
 * once the customer has paid the code, the row may never be guessed into a
 * terminal failure.
 *
 * <ul>
 *   <li>{@link Result#CONFIRMED} — the product service marked the order paid;
 *       {@code confirmationNumber} carries its confirmation handle.</li>
 *   <li>{@link Result#ALREADY_CONFIRMED} — the product service reports this
 *       exact payment was already applied (idempotent replay). Treated as
 *       success.</li>
 *   <li>{@link Result#REJECTED} — a definite refusal (amount mismatch, order
 *       cancelled/expired, paid under a different reference). {@code reason}
 *       carries the upstream code/message. The row parks
 *       COMPLETED_UNCONFIRMED — an operator or a later retry resolves it.</li>
 *   <li>{@link Result#UNREACHABLE} — the product service could not be reached
 *       or answered 5xx; the confirm may succeed on a later retry. Also parks
 *       COMPLETED_UNCONFIRMED, never guessed further.</li>
 * </ul>
 */
public record ConfirmOutcome(Result result, String confirmationNumber, String reason) {

    public enum Result { CONFIRMED, ALREADY_CONFIRMED, REJECTED, UNREACHABLE }

    public static ConfirmOutcome confirmed(String confirmationNumber) {
        return new ConfirmOutcome(Result.CONFIRMED, confirmationNumber, null);
    }

    public static ConfirmOutcome alreadyConfirmed(String confirmationNumber) {
        return new ConfirmOutcome(Result.ALREADY_CONFIRMED, confirmationNumber, null);
    }

    public static ConfirmOutcome rejected(String reason) {
        return new ConfirmOutcome(Result.REJECTED, null, reason);
    }

    public static ConfirmOutcome unreachable(String reason) {
        return new ConfirmOutcome(Result.UNREACHABLE, null, reason);
    }

    /** True when the order is confirmed (fresh confirm or idempotent replay). */
    public boolean succeeded() {
        return result == Result.CONFIRMED || result == Result.ALREADY_CONFIRMED;
    }
}
