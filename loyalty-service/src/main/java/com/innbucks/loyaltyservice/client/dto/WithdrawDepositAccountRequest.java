package com.innbucks.loyaltyservice.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/**
 * Body for {@code POST /internal/transfers/withdraw} on Oradian
 * middleware. Mirrors the middleware's
 * {@code EnterWithdrawalOnDepositAccountRequest} shape.
 *
 * <p>{@code overrideLimitCheck} stays in this DTO (unlike the credit
 * counterpart) because the upstream Oradian operation honours it. The
 * loyalty-points spend path always passes {@code false} — Oradian's
 * configured per-product / per-branch limits should still apply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawDepositAccountRequest(
        Boolean overrideLimitCheck,
        String accountID,
        String paymentMethodName,
        LocalDate transactionDate,
        String amount,
        String transactionBranchID,
        String notes
) {
}
