package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/**
 * Response from {@code POST /internal/transfers/credit} — Oradian
 * echoes the request fields plus the assigned identifiers
 * ({@code transactionID}, {@code commandID}, {@code referenceNumber}).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} shields us
 * from the cheque / FX / pending-processing fields Oradian's full
 * response carries but a loyalty-points credit never uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditDepositAccountResponse(
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
