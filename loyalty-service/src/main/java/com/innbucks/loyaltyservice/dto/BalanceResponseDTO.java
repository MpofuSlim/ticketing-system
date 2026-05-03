package com.innbucks.loyaltyservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponseDTO {

    private String customerId;
    private String tenantId;
    private BigDecimal balance;
}
