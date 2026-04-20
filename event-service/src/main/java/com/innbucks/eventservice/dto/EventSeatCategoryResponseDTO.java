package com.innbucks.eventservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSeatCategoryResponseDTO {
    private String name;
    private String description;
    private BigDecimal categoryPrice;
    private List<EventSectionResponseDTO> sections;
}
