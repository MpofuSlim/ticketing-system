package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /seat-categories/internal/price-by-name} (TIC-71): set the
 * full price of the category with this name on each listed event. Sent by
 * event-service when an organizer changes a price on every future date of a
 * series; the dates' categories were copied from the template by name.
 *
 * <p>No bean-validation annotations, on purpose: the controller checks the
 * internal token first and only then the fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PriceByNameRequest",
        description = "Set the full price of the named category on each event. Internal: X-Internal-Token.")
public class PriceByNameRequestDTO {

    @Schema(description = "The events, at most 500.", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private List<UUID> eventIds;

    @Schema(description = "The category's name, matched without regard to letter case or surrounding spaces.",
            example = "VIP")
    private String categoryName;

    @Schema(description = "The new full price, greater than 0, at most 2 decimals.", example = "150.00")
    private BigDecimal price;

    @Schema(description = "Who asked: recorded in each category's price history. The organizer's uuid, or a "
            + "staff member's email.", example = "9f0e1d2c-3b4a-4958-8776-655443322110")
    private String changedBy;
}
