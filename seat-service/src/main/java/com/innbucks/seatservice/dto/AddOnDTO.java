package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** An add-on as every category answer shows it (V12). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "AddOn", description = "An extra a buyer can add to each ticket of the category, at most one per ticket.")
public class AddOnDTO {

    @Schema(example = "a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01")
    private UUID id;

    @Schema(example = "Parking pass")
    private String name;

    @Schema(example = "One car, Lot B", nullable = true)
    private String description;

    @Schema(example = "5.00")
    private BigDecimal price;

    @Schema(example = "50", nullable = true, description = "How many may sell in all; null = no limit.")
    private Integer maxQuantity;

    @Schema(example = "12", nullable = true,
            description = "Held or sold (pending and paid bookings); null when the count could not be read.")
    private Long sold;

    @Schema(example = "38", nullable = true, description = "maxQuantity - sold; null with no limit or no count.")
    private Long remaining;

    @Schema(example = "false", description = "True when remaining is 0.")
    private boolean soldOut;
}
