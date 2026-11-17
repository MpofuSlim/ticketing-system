package innbucks.paymentservice.client;

import java.math.BigDecimal;

/**
 * Outcome of {@code GET /v1/checkouts/{id}/payment} (COPYandPAY status).
 *
 * <p><b>This read is one-shot.</b> Per the doc, "once a status response is
 * successful the checkout identifier can't be used anymore" — after it, the
 * checkout is dead and lookups must go to the Transaction Reports endpoint.
 * Callers must therefore persist this outcome in the transaction that
 * consumes it, never re-derive it later from the same checkout.
 *
 * @param outcome         classified {@link ZimswitchResultCode}
 * @param transactionId   the gateway's {@code id} for the TRANSACTION (distinct
 *                        from the checkout id — one checkout can produce
 *                        several transactions when the shopper reloads or
 *                        retries the form). This is the value to store as the
 *                        upstream reference and quote in disputes
 * @param amountEcho      the amount the gateway echoed, in MAJOR units. Callers
 *                        MUST cross-check it against what was sent — the doc
 *                        explicitly recommends verifying ID(s), amount,
 *                        currency, brand and type. Null when absent
 * @param currencyEcho    echoed currency, cross-checked alongside the amount
 * @param brand           card brand actually used (e.g. VISA) — logged, and
 *                        part of the recommended echo verification
 * @param paymentType     echoed payment type (expect {@code DB})
 * @param merchantTransactionId our {@code TKT-PMT-<uuid>} reference, echoed back
 * @param resultCode      raw {@code result.code} (journalled verbatim)
 * @param resultMessage   raw {@code result.description}
 * @param ndc             gateway correlation handle for support
 * @param parameterErrors {@code result.parameterErrors} rendered as
 *                        {@code name: message; name: message}: which request
 *                        parameter the gateway refused, and why. The values are
 *                        left out on purpose (they echo what was sent). Null
 *                        when the reply carries none. Journalled and logged so
 *                        a {@code 200.300.404} explains itself.
 */
public record CardPaymentStatus(
        ZimswitchResultCode outcome,
        String transactionId,
        BigDecimal amountEcho,
        String currencyEcho,
        String brand,
        String paymentType,
        String merchantTransactionId,
        String resultCode,
        String resultMessage,
        String ndc,
        String parameterErrors) {
}
