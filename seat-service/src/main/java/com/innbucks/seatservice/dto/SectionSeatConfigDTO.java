package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(name = "SectionSeatConfig",
        description = "Defines one physical section within a seat category and how many seats it contains.")
public class SectionSeatConfigDTO {

    @Schema(example = "A", description = "Section label (e.g. A, B, GA, FLOOR). Shown on the ticket.")
    @NotBlank(message = "Section is required")
    private String section;

    @Schema(example = "25", description = "Number of seats to generate in this section. Minimum 1.")
    @Min(value = 1, message = "Seat count must be at least 1")
    private int seatCount;
}
