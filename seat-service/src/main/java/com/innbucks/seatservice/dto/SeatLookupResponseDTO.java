package com.innbucks.seatservice.dto;

import com.innbucks.seatservice.entity.Seat;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLookupResponseDTO {

    private UUID seatId;
    private UUID eventId;
    private UUID categoryId;
    private String categoryName;
    private String sectionLabel;
    private Integer seatNumber;
    private BigDecimal price;
    private Seat.SeatStatus status;
}
