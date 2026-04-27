package com.innbucks.bookingservice.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

// Mirror of seat-service's SeatLookupResponseDTO.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLookupResponseDTO {

    public enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

    private UUID seatId;
    private UUID eventId;
    private UUID categoryId;
    private String categoryName;
    private String sectionLabel;
    private Integer seatNumber;
    private BigDecimal price;
    private SeatStatus status;
}
