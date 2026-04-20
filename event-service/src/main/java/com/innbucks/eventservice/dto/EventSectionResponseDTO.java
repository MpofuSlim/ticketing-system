package com.innbucks.eventservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSectionResponseDTO {
    private String section;
    private Integer seatCount;
    private BigDecimal price;
}
