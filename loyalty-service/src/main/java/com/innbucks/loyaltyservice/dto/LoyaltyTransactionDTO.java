package com.innbucks.loyaltyservice.dto;

import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyTransactionDTO {

    private Long id;
    private LoyaltyTransaction.Type type;
    private BigDecimal points;
    private BigDecimal dollarAmount;
    private String reference;
    private BigDecimal balanceAfter;
    private LocalDateTime createdAt;
}
