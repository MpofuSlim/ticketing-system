package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyRuleResponse {
    private String tenantId;
    private BigDecimal earnRate;
    private BigDecimal redeemRate;
    private boolean active;
}
