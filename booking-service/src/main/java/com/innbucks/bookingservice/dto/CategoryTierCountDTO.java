package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Active (PENDING + CONFIRMED) tickets sold at one price tier of one seat category.")
public class CategoryTierCountDTO {

    @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11")
    private UUID categoryId;

    @Schema(example = "6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
    private UUID priceTierId;

    @Schema(example = "37")
    private long count;
}
