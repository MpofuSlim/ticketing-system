package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Body of {@code PUT /seat-categories/{id}/add-ons}: the whole list, in display order. */
@Data
@Schema(name = "AddOnsRequest",
        description = "The category's full list of add-ons, in the order to show them. Replaces the current "
                + "list; an empty list removes them all.")
public class AddOnsRequestDTO {

    public static final int MAX_ADD_ONS = 10;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "addOns is required (send [] to remove them all)")
    @Size(max = MAX_ADD_ONS, message = "At most 10 add-ons per category.")
    private List<AddOnRequestDTO> addOns;
}
