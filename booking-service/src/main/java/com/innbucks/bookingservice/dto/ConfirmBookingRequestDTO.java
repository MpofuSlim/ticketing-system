package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
//   - { pointsToUse: P }           → pure points, no points earned
//   - { pointsToUse: P, cashAmount: X } → split pay; only the cash portion earns
//
// pointsToUse * (1 / redeemRate) + cashAmount must equal the booking's
// totalAmount within a small tolerance, or the confirm is rejected with 400.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ConfirmBookingRequest",
        description = """
                Optional payment breakdown for PATCH /bookings/{id}/confirm.

                **Pure cash** (omit body or send only `cashAmount`): the full `totalAmount` is charged;
                loyalty points are earned on the cash spend.

                **Points only** (`pointsToUse` only): all points, no cash; points are NOT earned on a
                redemption-only transaction.

                **Split pay** (both fields): `pointsToUse / redeemRate + cashAmount` must equal
                `totalAmount` within $0.01; loyalty points are earned on the `cashAmount` portion only.
                """)
public class ConfirmBookingRequestDTO {

    @Schema(example = "500.0000", nullable = true,
            description = "Loyalty points to burn toward this booking. " +
                    "Converted to cash value using the programme's redeemRate.")
    @DecimalMin(value = "0.0", message = "pointsToUse must be >= 0")
    private BigDecimal pointsToUse;

    @Schema(example = "50.00", nullable = true,
            description = "Cash portion of the payment. For a pure-cash confirm, omit this field entirely.")
    @DecimalMin(value = "0.0", message = "cashAmount must be >= 0")
    private BigDecimal cashAmount;
}
