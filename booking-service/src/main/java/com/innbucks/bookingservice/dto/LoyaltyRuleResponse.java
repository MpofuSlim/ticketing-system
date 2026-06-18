package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyRuleResponse {
    private String tenantId;
    private BigDecimal earnRate;
    private BigDecimal redeemRate;
    private boolean active;
}
