package com.innbucks.eventservice.dto.series;

import com.innbucks.eventservice.dto.AvailabilityLevel;
import com.innbucks.eventservice.dto.EventPriceFromDTO;
import com.innbucks.eventservice.dto.SalesStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One date of a series in a buyer's date picker (TIC-73). The date is an
 * ordinary event: open it with {@code GET /events/{eventId}} and book it with
 * its eventId.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SeriesPublicDate")
public class SeriesPublicDateDTO {

    @Schema(description = "The date's event: GET /events/{eventId} shows it, POST /bookings books it.",
            example = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
    private UUID eventId;

    @Schema(description = "At the market offset, like every event time.", example = "2026-12-04T19:00:00+02:00")
    private LocalDateTime startDateTime;

    @Schema(example = "2026-12-04T21:00:00+02:00")
    private LocalDateTime endDateTime;

    @Schema(description = "Whether the date sells now: ON_SALE, NOT_YET_ON_SALE (sales open at "
            + "salesStartDateTime) or SALES_CLOSED (also while the series is paused). Worked out on every "
            + "request.", example = "ON_SALE")
    private SalesStatus salesStatus;

    @Schema(nullable = true, example = "2026-11-20T19:00:00+02:00")
    private LocalDateTime salesStartDateTime;

    @Schema(nullable = true, example = "2026-12-04T18:30:00+02:00")
    private LocalDateTime salesEndDateTime;

    @Schema(nullable = true, description = "The cheapest ticket now, as on event cards. Null when the date has "
            + "no ticket types yet or seat-service could not be asked.")
    private EventPriceFromDTO priceFrom;

    @Schema(example = "AVAILABLE")
    private AvailabilityLevel availability;
}
