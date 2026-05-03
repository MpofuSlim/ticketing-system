package com.innbucks.loyaltyservice.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemRequestDTO {

    @NotBlank
    private String customerId;

    @NotBlank
    private String tenantId;

    // Number of points to debit from the customer's balance. The dollar value
    // they offset is points / redeemRate, and is the caller's concern.
    @NotNull
    @DecimalMin(value = "0.0001", message = "points must be positive")
    private BigDecimal points;

    @NotBlank
    private String reference;
}
