package innbucks.paymentservice.client;

/**
 * Classified result of {@code GET /bank/api/transaction/inquiry} — the
 * reconciler's resolve-by-query primitive for PENDING / IN_DOUBT rows.
 *
 * <ul>
 *   <li>{@link Outcome#COMPLETED} — the bank HAS the transaction and it
 *       succeeded: money moved. {@code reference} is its transaction ref.</li>
 *   <li>{@link Outcome#FAILED} — the bank has it and it terminally failed:
 *       no money moved.</li>
 *   <li>{@link Outcome#NOT_FOUND} — the bank has no record of the
 *       {@code originalParticipantReference}: the submission never landed.</li>
 *   <li>{@link Outcome#UNKNOWN} — response didn't classify; leave the row
 *       alone and try again next sweep.</li>
 * </ul>
 */
public record BankInquiryResult(
        Outcome outcome,
        String reference,
        String code,
        String message
) {
    public enum Outcome { COMPLETED, FAILED, NOT_FOUND, UNKNOWN }
}
