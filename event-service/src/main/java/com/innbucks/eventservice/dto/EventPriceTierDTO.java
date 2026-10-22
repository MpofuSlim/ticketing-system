package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One price tier of a seat category on the event page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventPriceTier")
public class EventPriceTierDTO {

    /** Whether a tier is the price now, will be later, or is over. */
    public enum Status { ACTIVE, UPCOMING, ENDED, SOLD_OUT }

    @Schema(example = "Early bird")
    private String name;

    @Schema(example = "100.00")
    private BigDecimal price;

    @Schema(example = "2026-09-01T00:00:00+02:00", nullable = true,
            description = "When the tier opens, at the market's offset; null when it opens as the tiers before it close.")
    private LocalDateTime startsAt;

    @Schema(example = "2026-09-08T23:59:59+02:00", nullable = true,
            description = "When the tier closes, at the market's offset; null when it closes on quantity alone.")
    private LocalDateTime endsAt;

    @Schema(example = "100", nullable = true, description = "Tickets that sell at this tier; null when unlimited.")
    private Integer quantity;

    @Schema(example = "4", nullable = true,
            description = "Tickets one buyer may hold at this tier; their further tickets cost the next tier's price. "
                    + "Null when there is no limit.")
    private Integer maxPerCustomer;

    @Schema(example = "63", nullable = true,
            description = "Tickets left at this tier, up to 30 seconds old. Absent without a quantity or a count.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long remaining;

    @Schema(example = "ACTIVE", description = "ACTIVE: the price now. UPCOMING: not started yet, or waiting for an "
            + "earlier tier to close. ENDED, SOLD_OUT otherwise.")
    private Status status;
}
