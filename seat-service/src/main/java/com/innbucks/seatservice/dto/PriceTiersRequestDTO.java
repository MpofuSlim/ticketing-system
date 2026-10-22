package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Body of {@code PUT /seat-categories/{id}/price-tiers}: the whole list, in the order the tiers apply. */
@Data
@Schema(name = "PriceTiersRequest",
        description = "The category's full list of price tiers, in the order they apply. Replaces the "
                + "current list; an empty list removes every tier.")
public class PriceTiersRequestDTO {

    public static final int MAX_TIERS = 10;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "tiers is required (send [] to remove every tier)")
    @Size(max = MAX_TIERS, message = "At most 10 price tiers per category")
    private List<@Valid PriceTierRequestDTO> tiers;
}
