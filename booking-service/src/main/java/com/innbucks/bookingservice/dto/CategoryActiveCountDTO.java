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
@Schema(description = "Number of active (PENDING + CONFIRMED) booking items for one seat category.")
public class CategoryActiveCountDTO {

    @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11")
    private UUID categoryId;

    @Schema(example = "37")
    private long count;
}
