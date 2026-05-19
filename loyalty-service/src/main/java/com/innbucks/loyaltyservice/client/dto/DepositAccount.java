package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * One row in the response of
 * {@code GET /internal/customers/{msisdn}/deposits} on the Oradian
 * middleware. Used by {@code OradianAccountResolver} during lazy
 * discovery: walk the list, filter {@code productID == "LPW"}, store
 * the {@code ID} on {@code wallets.oradian_account_id}.
 *
 * <p>Field shapes match Oradian's wire format verbatim. {@code balance}
 * and the {@code is*} flags are strings ("7500.00", "true", "") because
 * Oradian sends them that way; we never reinterpret here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DepositAccount(
        String internalID,
        String ID,
        String externalAccountNumber,
        String clientInternalID,
        String productID,
        String productName,
        String balance,
        String currencyCode,
        String status,
        String isMainAccount,
        String isMessagingFeeAccount,
        String isJointAccount,
        String subscribed,
        LocalDate appliedDate,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate closeDate
) {
}
