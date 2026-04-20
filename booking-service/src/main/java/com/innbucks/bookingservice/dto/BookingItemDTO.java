package com.innbucks.bookingservice.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingItemDTO {
    private UUID seatId;
    private UUID categoryId;
    private String categoryName;
    private String rowLabel;
    private Integer seatNumber;
    private BigDecimal priceAtBooking;
    private String ticketNumber; // e.g. 20260419-48291X
}
