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
    private UUID eventId;
    private String confirmationNumber;
    private Booking.BookingStatus status;
    private BigDecimal totalAmount;
    private List<BookingItemDTO> items; // each item has its own ticketNumber
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // Set on PENDING bookings; the seat hold lapses at this instant and the
    // expiration scheduler will flip the booking to CANCELLED. Null after
    // the booking is paid (CONFIRMED) or once it's been cancelled.
    private LocalDateTime expiresAt;
}
