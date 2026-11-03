package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** One add-on in {@code PUT /seat-categories/{id}/add-ons}; checked by the service, which names it. */
@Data
@Schema(name = "AddOnRequest")
public class AddOnRequestDTO {

    @Schema(example = "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01", nullable = true,
            description = "Send it to keep an add-on (its sales stay counted); leave it out for a new one.")
    private UUID id;

    @Schema(example = "Parking pass", description = "At most 60 characters, unique in the category.")
    private String name;

    @Schema(example = "One car, Lot B", nullable = true, description = "At most 200 characters.")
    private String description;

    @Schema(example = "5.00", description = "0 or more, at most 2 decimals.")
    private BigDecimal price;

    @Schema(example = "50", nullable = true,
            description = "Optional limit, 1 up to the category's tickets; leave it out for no limit.")
    private Integer maxQuantity;
}
