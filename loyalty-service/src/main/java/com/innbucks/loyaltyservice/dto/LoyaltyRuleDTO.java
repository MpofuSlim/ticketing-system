package com.innbucks.loyaltyservice.dto;

import jakarta.validation.constraints.DecimalMin;
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
public class LoyaltyRuleDTO {

    private String tenantId;

    // Points credited per $1 of cash spent. Must be > 0.
    @NotNull
    @DecimalMin(value = "0.0001", message = "earnRate must be positive")
    private BigDecimal earnRate;

    // Points required to offset $1 at redemption. Must be > 0.
    @NotNull
    @DecimalMin(value = "0.0001", message = "redeemRate must be positive")
    private BigDecimal redeemRate;

    private boolean active;
}
