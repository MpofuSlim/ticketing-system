package com.innbucks.bookingservice.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Body of the internal {@code PATCH /bookings/internal/{id}/extend-hold} call
 * payment-service makes right before minting an InnBucks payment code.
 * {@code holdUntil} is a zone-less UTC timestamp (house convention) and must
 * be in the future — extending a hold to a past timestamp would silently
 * shrink the booking's expiry window instead of growing it. Bean-validation
 * fires only when the controller binds @Valid; BookingService.extendHold
 * re-checks at the service layer as defence-in-depth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtendHoldRequestDTO {

    @NotNull(message = "holdUntil is required")
    @Future(message = "holdUntil must be in the future")
    private LocalDateTime holdUntil;
}
