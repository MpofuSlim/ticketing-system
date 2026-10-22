package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDTO {

    private UUID id;
    private String userEmail;
    // The purchaser's full name (V22). Null only on bookings that pre-date it.
    private String customerName;
    // Set from the JWT phoneNumber claim at booking time. May be null for
    // system users or older tokens that don't carry the claim.
    private String phoneNumber;
    private UUID eventId;
    private String confirmationNumber;
    private Booking.BookingStatus status;
    private BigDecimal totalAmount;
    // Stable cross-service organizer identifier — mirrors events.tenant_user_uuid.
    // Captured at booking creation via EventLookupDTO; the loyalty attribution
    // key and the ticket-scan ownership pointer.
    private UUID tenantUserUuid;
    // Set after a confirm that included points. Null on PENDING/cancelled
    // bookings and pure-cash confirmations.
    private BigDecimal pointsUsed;
    // Set after confirm. Equals totalAmount on pure-cash, less than totalAmount
    // when points were used.
    private BigDecimal cashAmount;
    // The promo code the booking used (V27) and the discount it gave, already
    // taken off totalAmount. Absent when no code was used.
    @io.swagger.v3.oas.annotations.media.Schema(example = "EARLY10", nullable = true,
            description = "The promo code this booking used; absent when none.")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String promoCode;
    @io.swagger.v3.oas.annotations.media.Schema(example = "20.00", nullable = true,
            description = "The promo code's discount on the whole booking, already taken off totalAmount; "
                    + "absent when no code was used.")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private BigDecimal discountAmount;
    private List<BookingItemDTO> items; // each item has its own ticketNumber
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Set on PENDING bookings; the seat hold lapses at this instant and the
    // expiration scheduler will flip the booking to CANCELLED. Null after
    // the booking is paid (CONFIRMED) or once it's been cancelled.
    private LocalDateTime expiresAt;
}
