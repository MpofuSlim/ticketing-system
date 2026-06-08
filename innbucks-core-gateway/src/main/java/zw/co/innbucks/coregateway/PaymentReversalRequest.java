package zw.co.innbucks.coregateway;

import zw.co.innbucks.core.dto.enums.Currency;

import java.math.BigDecimal;

/**
 * Inbound request body for {@code POST /payments/{originalPaymentReference}/reverse}.
 *
 * <p>A reversal is a NEW transaction in the veengu ledger (its own
 * {@code reference} + audit row) that points back at the original via
 * {@code originalReference}. payment-service models this as a new row in its
 * {@code payment} table with {@code parent_payment_reference} set — never
 * mutates the original row's status.
 *
 * <p>Partial refunds are supported by passing an {@code amount} less than the
 * original debit; veengu validates the cumulative reversed amount cannot exceed
 * the original.
 *
 * @param reversalReference  NEW caller-assigned reference for THIS reversal;
 *                           idempotency key (same contract as
 *                           {@link PaymentDebitRequest#paymentReference}).
 * @param customerMsisdn     Customer phone in E.164 — must match the original
 *                           debit. Sent as veengu's {@code msisdn}.
 * @param customerAccount    Customer wallet account being credited back. Sent
 *                           as veengu's {@code destinationAccount} (on a
 *                           reversal the direction flips).
 * @param merchantAccount    Merchant wallet being debited. Sent as veengu's
 *                           {@code sourceAccount}.
 * @param amount             Reversal amount (full or partial), strictly
 *                           positive, &le; original debit.
 * @param currency           Must match original debit's currency.
 * @param narration          Reason for refund (operator-supplied). Shows on
 *                           the customer's statement.
 * @param participantId      Merchant participant ID. Same value as the
 *                           original debit.
 */
record PaymentReversalRequest(
        String reversalReference,
        String customerMsisdn,
        String customerAccount,
        String merchantAccount,
        BigDecimal amount,
        Currency currency,
        String narration,
        String participantId
) {}
