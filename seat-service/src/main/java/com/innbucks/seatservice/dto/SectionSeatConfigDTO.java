package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(name = "SectionSeatConfig",
        description = "Defines one physical section within a seat category and how many seats it contains.")
public class SectionSeatConfigDTO {

    public static final long MAX_SEATS_PER_SECTION = 100_000L;

    @Schema(example = "A", description = "Section label (e.g. A, B, GA, FLOOR). Shown on the ticket. Max 50 characters.")
    @NotBlank(message = "Section is required")
    @Size(max = 50, message = "Section label must be at most 50 characters")
    private String section;

    @Schema(example = "25",
            description = "Number of seats to generate in this section. Must be between 1 and 100,000.")
    @Min(value = 1, message = "Seat count must be at least 1")
    @Max(value = MAX_SEATS_PER_SECTION, message = "Seat count must be at most 100,000 per section")
    private int seatCount;

    @Schema(example = "https://cdn.innbucks.co.zw/sections/vip-a.png", nullable = true,
            description = "Optional URL of an image for this section (e.g. a seat-map thumbnail the FE "
                    + "renders next to the section). Host the image elsewhere and pass the link; max 1024 "
                    + "characters. Echoed back on reads.")
    @Size(max = 1024, message = "Section image URL must be at most 1024 characters")
    private String imageUrl;
}
