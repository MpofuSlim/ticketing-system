package com.innbucks.seatservice.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SectionSeatConfigDTO {

    @NotBlank(message = "Section is required")
    private String section;

    @Min(value = 1, message = "Seat count must be at least 1")
    private int seatCount;
}
