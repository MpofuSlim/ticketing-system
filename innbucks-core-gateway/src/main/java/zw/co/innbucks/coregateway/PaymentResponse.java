package zw.co.innbucks.coregateway;

/**
 * Outbound response envelope for {@code /payments/*} endpoints.
 *
 * <p>The {@code outcome} field is the single source of truth — caller switches
 * on it to decide ledger state and customer-facing behaviour. {@code error} is
 * a human-readable string for logs and support tickets; the structured
 * {@code upstreamCode} + {@code upstreamMessage} fields are veengu's own error
 * envelope (from {@code zw.co.innbucks.core.dto.veengu.ErrorResponse}) when
 * the call failed.
 *
 * <p>On success: {@code upstreamReference} carries veengu's internal
 * transaction id (the {@code id} field on the response {@code TransactionDto}
 * — what reconciliation queries veengu with later when the GET-status
 * endpoint lands).
 *
 * <p>HTTP status convention (mirrors {@link SmsController} / siblings):
 * <ul>
 *   <li>200 — call reached veengu and the gateway has a verdict (success OR a
 *       terminal/duplicate rejection); the {@code outcome} field disambiguates.</li>
 *   <li>503 — call never reached veengu (discovery / connectivity failure) OR
 *       veengu was unavailable (5xx). {@code outcome=UPSTREAM_UNAVAILABLE}.</li>
 * </ul>
 *
 * We deliberately do NOT use 502 for veengu's 4xx rejections — those are
 * authoritative business outcomes the caller must persist, not gateway errors.
 * Returning 200 with a {@code REJECTED_*} outcome keeps Resilience4j retry off
 * them (no 5xx to trigger on) and lets payment-service classify cleanly.
 */
record PaymentResponse(
        String paymentReference,
        PaymentOutcome outcome,
        String upstreamReference,
        String upstreamCode,
        String upstreamMessage,
        String error
) {}
