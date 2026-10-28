package innbucks.paymentservice.client;

import java.math.BigDecimal;

/**
 * Trimmed view of an EIP charge/Query response — ONLY the load-bearing
 * fields. The raw envelope carries ~30 fields, several of which the PDF's
 * own samples prove unreliable (identity fields come back swapped, the
 * country code is dropped, timestamps are 0/null) and one of which is the
 * merchant PIN — which is why callers get this record and never the raw
 * body, and why the raw body is never logged (docs/api/ecocash-eip.md).
 *
 * @param outcome        classified {@code transactionOperationStatus} — the
 *                       ONLY outcome field on this rail
 * @param rawStatus      the verbatim status string, for journals/logs
 * @param ecocashReference EcoCash's transaction reference
 *                       (serverReferenceCode/ecocashReference) when present —
 *                       the future-refund handle
 * @param amountEcho     {@code paymentAmount.charginginformation.amount}
 * @param totalAmountCharged {@code paymentAmount.totalAmountCharged} — 0.0
 *                       until approved; the echo-guard field on a COMPLETED
 *                       read
 * @param currencyEcho   {@code paymentAmount.charginginformation.currency}
 * @param responseMessage EcoCash's own words for the outcome, e.g. "You have
 *                       insufficient funds to complete this transaction." on a
 *                       FAILED read — cleaned and capped by the client, null
 *                       when absent. Shown to the customer, so never the body.
 */
public record EcocashChargeStatus(
        Outcome outcome,
        String rawStatus,
        String ecocashReference,
        BigDecimal amountEcho,
        BigDecimal totalAmountCharged,
        String currencyEcho,
        String responseMessage) {

    /** A status with no message from EcoCash. */
    public EcocashChargeStatus(Outcome outcome, String rawStatus, String ecocashReference,
                               BigDecimal amountEcho, BigDecimal totalAmountCharged, String currencyEcho) {
        this(outcome, rawStatus, ecocashReference, amountEcho, totalAmountCharged, currencyEcho, null);
    }

    /**
     * Classified {@code transactionOperationStatus}. The still-pending set is
     * deliberately open-ended (the PDF names both "PENDING SUBSCRIBER
     * VALIDATION" and "CHARGED" as initial states): anything that is not
     * COMPLETED or FAILED is PENDING, and an unreadable answer is UNKNOWN —
     * which never expires a row.
     */
    public enum Outcome { COMPLETED, FAILED, PENDING, NOT_FOUND, UNKNOWN }
}
