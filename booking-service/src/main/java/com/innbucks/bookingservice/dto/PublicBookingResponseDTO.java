package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Trimmed, PII-free view of a booking for the PUBLIC (no-auth) lookup
 * {@code GET /bookings/public/{id}} — a magic-link / unauthenticated view that
 * shows the customer their booking + tickets without exposing personal or
 * internal data to anyone who happens to hold the UUID.
 *
 * <p>Deliberately OMITS, vs the authenticated {@link BookingResponseDTO}:
 * <ul>
 *   <li>{@code userEmail}, {@code phoneNumber} — customer PII;</li>
 *   <li>{@code tenantId} — internal loyalty-attribution concept;</li>
 *   <li>{@code pointsUsed}, {@code cashAmount} — the payment split (financial
 *       detail not needed for a ticket view; {@code totalAmount} is kept).</li>
 * </ul>
 * Keeps what a ticket view needs: confirmation number, status, total, the
 * tickets themselves (each with its scannable QR), and the booking timeline.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicBookingResponseDTO {

    @Schema(description = "Booking id (the lookup key).",
            example = "a3b9c1d2-1234-5678-9abc-def012345678")
    private UUID id;

    @Schema(description = "Event the booking is for.",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID eventId;

    @Schema(description = "Human-facing confirmation reference.", example = "INN-20260502-AB12CD")
    private String confirmationNumber;

    @Schema(description = "Booking lifecycle status.", example = "CONFIRMED")
    private Booking.BookingStatus status;

    @Schema(description = "Total amount for the booking.", example = "100.00")
    private BigDecimal totalAmount;

    @Schema(description = "The booked tickets — each carries its ticket number and a scannable QR.")
    private List<BookingItemDTO> items;

    @Schema(description = "When the booking was created (UTC).", example = "2026-05-02T15:45:00")
    private LocalDateTime createdAt;

    @Schema(description = "Seat-hold expiry for a PENDING booking (UTC); null once CONFIRMED/cancelled.",
            example = "2026-05-02T15:50:00")
    private LocalDateTime expiresAt;
}
