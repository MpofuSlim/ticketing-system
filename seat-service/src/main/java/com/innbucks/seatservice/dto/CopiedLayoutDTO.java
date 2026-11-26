package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * What {@code POST /seat-categories/internal/copy} did: whether this call
 * copied the layout, and which target category stands for each source one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CopiedLayout")
public class CopiedLayoutDTO {

    @Schema(description = "True when this call copied the layout. False when the target already had categories "
            + "(a repeated call): nothing was copied and the mapping is by category name.")
    private boolean copied;

    @Schema(description = "One entry per source category that has a counterpart on the target.")
    private List<CopiedCategoryDTO> categories;
}
