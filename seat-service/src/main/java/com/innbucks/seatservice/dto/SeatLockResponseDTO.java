package com.innbucks.seatservice.dto;

import lombok.*;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLockResponseDTO {

    private UUID seatId;
    private String sectionLabel;
    private Integer seatNumber;
    private String categoryName;
    private String status;
    private String message;
    private long expiresInSeconds; // how long the lock lasts
}
