package com.innbucks.bookingservice.dto;

import lombok.*;

import java.util.UUID;

// Mirror of seat-service's SeatResponseDTO. Returned by GET /seats/available
// and used by booking-service to pick a random seat for a category.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableSeatDTO {

    private UUID id;
    private UUID categoryId;
    private String categoryName;
    private String sectionLabel;
    private Integer seatNumber;
    private String status;
}
