package com.innbucks.seatservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// Mirror of booking-service's CategoryBookingDTO. Returned by
// GET /bookings/by-category/{id}; used to populate the analytics response.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryBookingDTO {

    public enum BookingStatus { PENDING, CONFIRMED, CANCELLED }

    private UUID bookingId;
    private String userEmail;
    private UUID eventId;
    private BookingStatus status;
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
}
