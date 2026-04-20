package com.innbucks.seatservice.dto;

import com.innbucks.seatservice.entity.Seat;
import lombok.*;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatResponseDTO {

    private UUID id;
    private UUID categoryId;
    private String categoryName;
    private String sectionLabel;
    private Integer seatNumber;
    private Seat.SeatStatus status;
}
