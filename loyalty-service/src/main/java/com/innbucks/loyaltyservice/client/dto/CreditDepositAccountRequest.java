package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Body for {@code POST /internal/transfers/credit} on Oradian middleware.
 * Mirrors the middleware's {@code EnterDepositOnDepositAccountRequest}
 * shape; field names + types match the wire format verbatim.
 *
 * <p>{@code amount} is a {@link String} (not {@link java.math.BigDecimal})
 * because Oradian's RPC API rejects unquoted numbers on some products;
 * keeping it as a String all the way through avoids float-format
 * surprises.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditDepositAccountRequest(
        String accountID,
        String paymentMethodName,
        LocalDate transactionDate,
        String amount,
        String transactionBranchID,
        String notes,
        String referenceNumber
) {
}
