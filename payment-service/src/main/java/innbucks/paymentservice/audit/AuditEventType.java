package innbucks.paymentservice.audit;

/**
 * Enumeration of event types written to {@code audit_events} by
 * payment-service (OWASP A09 — tamper-evident trail for money-movement
 * events: 2D-code generation, payment confirmation/failure, and
 * settlement-reconciliation discrepancies).
 *
 * <p>Adding a new value here is the right way to surface a new
 * sensitive action — the underlying column is {@code VARCHAR(64)}
 * to keep this flexible without an enum-table join.
 *
 * <p>Naming convention: {@code <DOMAIN>_<ACTION>_<RESULT?>} where
 * the suffix is omitted when there's no SUCCESS / FAILURE polarity
 * worth distinguishing in the type itself (the {@code outcome}
 * column carries that).
 */
public enum AuditEventType {
    /** An InnBucks 2D payment code was generated for a booking (a live,
     *  customer-payable code was minted). */
    PAYMENT_CODE_GENERATED,
    /** Generation of an InnBucks 2D payment code failed (upstream error,
     *  amount-echo mismatch, or a guard rejection). No live code was minted. */
    PAYMENT_CODE_GENERATION_FAILED,
    /** A payment was confirmed as paid — the upstream status inquiry resolved
     *  to a successful, settled transaction and money moved to the merchant. */
    PAYMENT_CONFIRMED,
    /** A payment resolved to a terminal failed/expired state — no money moved
     *  (customer abandoned, code expired, or upstream rejected). */
    PAYMENT_FAILED,
    /** The upstream status inquiry returned an UNKNOWN/unclassifiable outcome —
     *  money MAY have moved. The row is deliberately never auto-expired; a
     *  blocked slot beats a double charge. */
    PAYMENT_STATUS_UNKNOWN,
    /** Settlement reconciliation found a discrepancy between our ledger and the
     *  InnBucks statement (ours-not-theirs, theirs-not-ours, amount mismatch).
     *  Any occurrence is a money incident. */
    PAYMENT_RECON_DISCREPANCY,
    /** X-Internal-Token validation failed on an internal payment endpoint —
     *  the S2S trust boundary was probed or a caller is misconfigured. The
     *  {@code failure_reason} narrows it; {@code metadata} carries the path,
     *  NEVER the token itself. */
    PAYMENT_INTERNAL_TOKEN_FAILURE
}
