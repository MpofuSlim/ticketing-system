package com.innbucks.eventservice.dto.series;

import com.innbucks.eventservice.dto.SalesStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One date of a series: an ordinary event, read in full with GET /events/{eventId}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SeriesDate")
public class SeriesDateDTO {

    @Schema(example = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
    private UUID eventId;

    @Schema(description = "At the market offset, like every event time.", example = "2026-12-04T19:00:00+02:00")
    private LocalDateTime startDateTime;

    @Schema(example = "2026-12-04T21:00:00+02:00")
    private LocalDateTime endDateTime;

    @Schema(example = "ON_SALE")
    private SalesStatus salesStatus;

    @Schema(description = "Whether the date is published.", example = "true")
    private boolean active;

    @Schema(description = "Whether the series' pause closed this date's sales (TIC-71). Resuming opens them.",
            example = "false")
    private boolean salesPaused;
}
