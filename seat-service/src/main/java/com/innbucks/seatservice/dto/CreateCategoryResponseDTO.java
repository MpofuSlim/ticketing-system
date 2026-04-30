package com.innbucks.seatservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCategoryResponseDTO {

    private UUID seatCategoryId;
    private UUID eventId;
    private String name;
    private String description;
    private BigDecimal price;
    private List<SectionSeatConfigDTO> sections;
}
