package com.innbucks.eventservice.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class SeatCategoryServiceResponseDTO {
    private UUID eventId;
    private String name;
    private String description;
    private BigDecimal price;
    private List<SeatCategorySectionDTO> sections;
}
