package zw.co.innbucks.coregateway;

/**
 * Outcome of a veengu transaction submission, as the gateway sees it after
 * classifying veengu's response (or absence of one). Drives caller behaviour in
 * payment-service:
 *
 * <ul>
 *   <li>{@code COMPLETED} — veengu accepted the debit/reversal authoritatively;
 *       caller may mark its local ledger SUCCEEDED and confirm the booking.</li>
 *   <li>{@code PROCESSING} — veengu received the request but the outcome isn't
 *       yet authoritative; caller leaves PENDING and the reconciler resolves
 *       later.</li>
 *   <li>{@code DUPLICATE_DETECTED} — veengu rejected the request as a duplicate
 *       (its {@code RESOURCE_ALREADY_EXISTS}). The original submission landed;
 *       the caller should query veengu for true state instead of marking
 *       FAILED. Always recoverable.</li>
 *   <li>{@code REJECTED_*} — terminal rejection by veengu for the specific
 *       reason. Caller marks FAILED, does NOT retry; the customer / operator
 *       must act on the cause (top up, unlock, fix request).</li>
 *   <li>{@code UPSTREAM_UNAVAILABLE} — couldn't get an authoritative answer
 *       (5xx, network error, timeout). Caller leaves PENDING; reconciler /
 *       Resilience4j retry will resolve.</li>
 * </ul>
 *
 * The {@code retryable} attribute is the single bit payment-service needs to
 * decide whether to leave a PENDING row for the reconciler or close it out
 * immediately.
 */
enum PaymentOutcome {

    COMPLETED(false),
    PROCESSING(false),

    REJECTED_INSUFFICIENT_FUNDS(false),
    REJECTED_ACCOUNT_UNAVAILABLE(false),
    REJECTED_LIMIT_REACHED(false),
    REJECTED_CURRENCY(false),
    REJECTED_VALIDATION(false),
    REJECTED_NOT_AUTHORIZED(false),
    REJECTED_OTHER(false),

    DUPLICATE_DETECTED(false),

    UPSTREAM_UNAVAILABLE(true);

    private final boolean retryable;

    PaymentOutcome(boolean retryable) {
        this.retryable = retryable;
    }

    boolean isRetryable() {
        return retryable;
    }

    boolean isTerminalRejection() {
        return name().startsWith("REJECTED_");
    }

    boolean isSuccess() {
        return this == COMPLETED;
    }
}
