package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * What the cheapest ticket to an event costs if booked now, for event cards:
 * "Early bird from $100". Worked out on every request from the event's seat
 * categories (a price tier closes on time on every replica), from the
 * categories that still have seats; the categories' layout and counts may be
 * up to 30 seconds old.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventPriceFrom",
        description = "The lowest price a ticket to this event costs if booked now, for cards and filters. "
                + "Null when the event has no seat categories, or seat-service could not be asked.")
public class EventPriceFromDTO {

    @Schema(example = "100.00",
            description = "What the cheapest ticket costs now: its category's current price, i.e. the price "
                    + "tier on sale, else the full price.")
    private BigDecimal currentPrice;

    @Schema(example = "150.00",
            description = "That category's full price. Equal to currentPrice when no tier applies; show it "
                    + "struck through when it is higher.")
    private BigDecimal fullPrice;

    @Schema(example = "Early bird", nullable = true,
            description = "The price tier currentPrice comes from; null at the full price.")
    private String tierName;

    @Schema(example = "VIP", description = "The category that cheapest ticket is in.")
    private String categoryName;

    @Schema(example = "false",
            description = "True when every category is sold out; the prices then describe the cheapest "
                    + "category anyway.")
    private boolean soldOut;
}
