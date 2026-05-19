package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response shape of {@code GET /internal/deposits/{accountId}} on the
 * Oradian middleware. Flat projection of Oradian's nested
 * LookupDepositAccount payload — the middleware strips the envelope
 * so callers don't walk it.
 *
 * <p>Used by {@code LoyaltyReconciliationJob} (finalising stale
 * PENDING rows) and {@code LoyaltyBalanceAuditJob} (drift detection
 * between local wallet.balance and Oradian-canonical balance).
 *
 * <p>{@code balance} stays as a String to match Oradian's wire format
 * exactly — call sites that need arithmetic parse it via
 * {@code new BigDecimal(snapshot.balance())}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepositAccountSnapshot(
        String ID,
        String balance,
        String status,
        String productID,
        String currencyCode,
        String clientID
) {
}
