package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How many tickets a date has left, as a level for a buyer's date picker
 * (TIC-73), never a count. Worked out from the seat categories' seats left,
 * which may be up to a minute old: display only, a booking claims its own
 * stock.
 */
@Schema(name = "AvailabilityLevel",
        description = "AVAILABLE: plenty left. FEW_LEFT: at most 10 tickets, or at most 10% of the tickets, "
                + "left. SOLD_OUT: none left. UNKNOWN: seat-service could not be asked, or the date has no "
                + "ticket types yet; show the date without a level.")
public enum AvailabilityLevel {
    AVAILABLE,
    FEW_LEFT,
    SOLD_OUT,
    UNKNOWN;

    /** At or below this many seats left, a date has few left. */
    public static final int FEW_LEFT_SEATS = 10;

    /** At or below this share of its allocated seats left, a date has few left. */
    public static final int FEW_LEFT_PERCENT = 10;
}
