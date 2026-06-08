package innbucks.paymentservice.client;

/**
 * Mirror of innbucks-core-gateway's {@code PaymentOutcome}. We carry our own
 * copy because the gateway is a separate Boot-3 module — we can't import its
 * package types here. The strings must match what the gateway emits in its
 * {@code PaymentResponse} envelope so Jackson can round-trip them as the
 * {@code outcome} field.
 *
 * <p>Caller maps each value to a local ledger transition + customer-facing
 * HTTP status. The {@code COMPLETED} / {@code REJECTED_*} / {@code PROCESSING}
 * / {@code UPSTREAM_UNAVAILABLE} distinction is the banking-discipline bit:
 * permanent rejections never get retried, transient failures leave the row
 * PENDING for the reconciler.
 */
public enum PaymentOutcome {
    COMPLETED,
    PROCESSING,
    REJECTED_INSUFFICIENT_FUNDS,
    REJECTED_ACCOUNT_UNAVAILABLE,
    REJECTED_LIMIT_REACHED,
    REJECTED_CURRENCY,
    REJECTED_VALIDATION,
    REJECTED_NOT_AUTHORIZED,
    REJECTED_OTHER,
    DUPLICATE_DETECTED,
    UPSTREAM_UNAVAILABLE
}
