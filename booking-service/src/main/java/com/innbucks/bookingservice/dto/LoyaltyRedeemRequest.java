package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyRedeemRequest {
    private UUID organizerUuid;
    private String phoneNumber;
    private BigDecimal points;
    private String reference;
}
