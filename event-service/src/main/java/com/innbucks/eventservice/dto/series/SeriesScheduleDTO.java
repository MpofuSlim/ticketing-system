package com.innbucks.eventservice.dto.series;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** A series' schedule as stored: the market's local wall clock, exactly as the organizer set it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SeriesSchedule", description = "Market-local, as set. Days in week order, times and dates sorted.")
public class SeriesScheduleDTO {

    @Schema(example = "[\"FRIDAY\",\"SATURDAY\"]")
    private List<String> daysOfWeek;

    @Schema(example = "[\"19:00\",\"21:30\"]")
    private List<String> startTimes;

    @Schema(example = "120")
    private int durationMinutes;

    @Schema(example = "2026-12-04")
    private LocalDate startDate;

    @Schema(nullable = true, example = "2027-03-26")
    private LocalDate endDate;

    @Schema(example = "[\"2026-12-25\"]")
    private List<String> skipDates;

    @Schema(example = "[\"2026-12-24T15:00\"]")
    private List<String> extraStarts;

    @Schema(example = "60")
    private int bookingWindowDays;

    @Schema(nullable = true, example = "20160")
    private Integer salesOpenMinutesBefore;

    @Schema(nullable = true, example = "30")
    private Integer salesCloseMinutesBefore;
}
