package zw.co.innbucks.coregateway;

import zw.co.innbucks.core.dto.enums.Currency;

import java.math.BigDecimal;

/**
 * Inbound request body for {@code POST /payments/debit}.
 *
 * <p>The {@code paymentReference} is the ticketing payment's stable identifier
 * AND the idempotency key — veengu's generic {@code TransactionDto} carries it
 * through as the {@code reference} field and rejects duplicates with
 * {@code RESOURCE_ALREADY_EXISTS} when {@code validateDuplicates=true} on the
 * merchant participant config. The caller MUST supply it (no auto-generation
 * here — payment-service owns the reference because it owns the ledger row;
 * letting the gateway invent one would break the link between the local
 * ledger and the veengu submission).
 *
 * <p>Identifiers are kept loosely-typed strings on purpose: veengu's account
 * model accepts InnBucks wallet account numbers, MSISDN-derived accounts, and
 * merchant accounts in the same field.
 *
 * @param paymentReference   Caller-assigned stable id. UUID-shaped. The gateway
 *                           passes this verbatim as veengu's {@code reference}
 *                           and echoes it on every response.
 * @param customerMsisdn     Customer phone in E.164 (e.g. +263771234567). Sent
 *                           as veengu's {@code msisdn} and {@code sourceMsisdn}.
 * @param customerAccount    Customer's InnBucks wallet account identifier. Sent
 *                           as veengu's {@code sourceAccount}. Resolved by
 *                           payment-service before calling here.
 * @param merchantAccount    Ticketing merchant wallet account identifier. Sent
 *                           as veengu's {@code destinationAccount}.
 * @param amount             Debit amount, strictly positive. NUMERIC precision
 *                           19,4 in the local ledger.
 * @param currency           ISO 4217 currency enum from the core jar. MUST
 *                           match the customer's wallet currency or veengu
 *                           returns {@code CURRENCY_NOT_SUPPORTED}.
 * @param narration          Free-text description that shows on the customer's
 *                           wallet statement. Keep short (under 64 chars to be
 *                           safe across rails).
 * @param participantId      Merchant participant ID assigned by InnBucks.
 *                           Optional in the request body (defaultable via env
 *                           on the gateway if a single deployment serves a
 *                           single merchant).
 */
record PaymentDebitRequest(
        String paymentReference,
        String customerMsisdn,
        String customerAccount,
        String merchantAccount,
        BigDecimal amount,
        Currency currency,
        String narration,
        String participantId
) {}
