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
public class EarnRequestDTO {

    @NotBlank
    private String customerId;

    @NotBlank
    private String tenantId;

    // The cash portion of the purchase. Points earned = cashAmount * earnRate.
    @NotNull
    @DecimalMin(value = "0.0001", message = "cashAmount must be positive")
    private BigDecimal cashAmount;

    // Idempotency key — typically the bookingId. Re-submitting with the same
    // reference is a no-op, not a double-credit.
    @NotBlank
    private String reference;
}
