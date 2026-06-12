package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Body of the internal {@code PATCH /bookings/internal/{id}/extend-hold} call
 * payment-service makes right before minting an InnBucks payment code.
 * {@code holdUntil} is a zone-less UTC timestamp (house convention).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtendHoldRequestDTO {
    private LocalDateTime holdUntil;
}
