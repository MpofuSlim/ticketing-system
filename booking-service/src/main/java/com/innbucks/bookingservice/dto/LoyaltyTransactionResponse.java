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
public class LoyaltyTransactionResponse {
    private Long id;
    private String type;
    private BigDecimal points;
    private BigDecimal balanceAfter;
}
