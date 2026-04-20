package com.innbucks.seatservice.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponseDTO {

    private UUID id;
    private UUID eventId;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer totalSeats;
    private Integer availableSeats;
    private LocalDateTime createdAt;
}
