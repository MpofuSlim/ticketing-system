package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * Response from {@code POST /internal/transfers/withdraw} — same shape
 * as {@link CreditDepositAccountResponse} (the upstream operations
 * mirror each other), kept as a distinct type so call sites can't
 * accidentally cross the streams.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawDepositAccountResponse(
        Boolean overrideLimitCheck,
        String accountID,
        String paymentMethodName,
        LocalDate transactionDate,
        String amount,
        String transactionBranchID,
        String notes,
        String referenceNumber,
        String transactionID,
        String commandID
) {
}
