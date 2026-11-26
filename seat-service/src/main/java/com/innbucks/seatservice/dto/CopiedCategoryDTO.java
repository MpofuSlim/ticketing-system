package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One source category and the target category that stands for it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CopiedCategory")
public class CopiedCategoryDTO {

    @Schema(example = "8f1d4a3e-1c0f-4d19-9a0b-1f4d9b6a7c11")
    private UUID sourceCategoryId;

    @Schema(example = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
    private UUID categoryId;

    @Schema(example = "VIP")
    private String name;
}
