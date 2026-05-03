package com.innbucks.bookingservice.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// Optional payload for PATCH /bookings/{id}/confirm.
//
// Three valid shapes:
//   - body omitted entirely        → pure cash, full totalAmount, points earned
//   - { cashAmount: X }            → pure cash, points earned
//   - { pointsToUse: P }           → pure points, no points earned (rule of the program)
//   - { pointsToUse: P, cashAmount: X } → split pay; only the cash portion earns
//
// pointsToUse * (1 / redeemRate) + cashAmount must equal the booking's
// totalAmount within a small tolerance, or the confirm is rejected with 400.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmBookingRequestDTO {

    @DecimalMin(value = "0.0", message = "pointsToUse must be >= 0")
    private BigDecimal pointsToUse;

    @DecimalMin(value = "0.0", message = "cashAmount must be >= 0")
    private BigDecimal cashAmount;
}
