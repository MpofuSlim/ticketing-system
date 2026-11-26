package com.innbucks.eventservice.dto.series;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * One month of a series' dates, for a buyer's date picker (TIC-73). Public:
 * only what an event card already shows, never the schedule, the organizer
 * or anything unpublished.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SeriesMonth")
public class SeriesMonthDTO {

    @Schema(example = "5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f")
    private UUID seriesId;

    @Schema(example = "Friday Comedy Night")
    private String title;

    @Schema(example = "Theatre in the Park, Harare")
    private String venue;

    @Schema(description = "The month shown, in the market's own calendar.", example = "2026-12")
    private String month;

    @Schema(description = "Every month that has a date a buyer can see, from now on, soonest first: the "
            + "picker's month list. Empty when the series has no such date.",
            example = "[\"2026-12\", \"2027-01\"]")
    private List<String> months;

    @Schema(description = "The month's dates that have not ended, soonest first. Empty when the month has none.")
    private List<SeriesPublicDateDTO> dates;
}
