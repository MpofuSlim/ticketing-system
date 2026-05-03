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
    // Set from the JWT phoneNumber claim at booking time. May be null for
    // system users or older tokens that don't carry the claim.
    private String phoneNumber;
    private UUID eventId;
    private String confirmationNumber;
    private Booking.BookingStatus status;
    private BigDecimal totalAmount;
    // Owning tenant of the event (used for loyalty attribution). Null for
    // bookings created before tenant capture, or when event-service was
    // unreachable at booking time.
    private String tenantId;
    // Set after a confirm that included points. Null on PENDING/cancelled
    // bookings and pure-cash confirmations.
    private BigDecimal pointsUsed;
    // Set after confirm. Equals totalAmount on pure-cash, less than totalAmount
    // when points were used.
    private BigDecimal cashAmount;
    private List<BookingItemDTO> items; // each item has its own ticketNumber
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Set on PENDING bookings; the seat hold lapses at this instant and the
    // expiration scheduler will flip the booking to CANCELLED. Null after
    // the booking is paid (CONFIRMED) or once it's been cancelled.
    private LocalDateTime expiresAt;
}
