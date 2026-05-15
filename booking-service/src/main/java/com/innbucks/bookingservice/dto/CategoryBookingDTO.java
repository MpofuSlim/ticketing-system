package com.innbucks.bookingservice.dto;

import com.innbucks.bookingservice.entity.Booking;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// One row per booked seat in a given category. Returned by
// GET /bookings/by-category/{id} for cross-service analytics. Includes both
// booking-level fields (who, when, status) and seat-level fields
// (seatId, ticketNumber) so the consumer can build a full picture.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryBookingDTO {

    private UUID bookingId;
    private String userEmail;
    private UUID eventId;
    private Booking.BookingStatus status;
    private String confirmationNumber;
    private UUID seatId;
    private UUID categoryId;
    private String categoryName;
    private String rowLabel;
    private Integer seatNumber;
    private String ticketNumber;
    private BigDecimal priceAtBooking;
    private LocalDateTime bookedAt;
    private LocalDateTime updatedAt;
    // For PENDING bookings: the instant the seat hold lapses. Null for
    // CONFIRMED (paid) and CANCELLED bookings.
    private LocalDateTime expiresAt;
}
