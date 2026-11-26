package com.innbucks.eventservice.dto.series;

import com.innbucks.eventservice.service.SeriesSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code PUT /events/series/{seriesId}/schedule} (TIC-71): the whole
 * schedule, as on create. A field left out is not "unchanged": send the full
 * schedule each time (GET the series for its current one).
 */
@Data
@Schema(name = "SeriesScheduleRequest", description = """
        The series' whole schedule, replacing the one it has. Same fields and rules as on POST /events/series, \
        market-local times and dates. Dates not made yet follow it. Dates already made that it no longer has are \
        removed when nobody holds a ticket for them, and kept when someone does.""")
public class SeriesScheduleRequestDTO {

    @Schema(description = "Required. The days of the week the series runs: MONDAY to SUNDAY. At least one.",
            example = "[\"FRIDAY\",\"SATURDAY\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> daysOfWeek;

    @Schema(description = "Required. Each day's start times, 24-hour HH:mm, market-local. 1 to 10.",
            example = "[\"19:00\",\"21:30\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> startTimes;

    @Schema(description = "Required. How long each NEW date lasts, in minutes (1 to 1440). Dates already made keep "
            + "their length.", example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "durationMinutes is required")
    @Min(value = 1, message = "Must be between 1 and " + SeriesSchedule.MAX_DURATION_MINUTES + " minutes.")
    @Max(value = SeriesSchedule.MAX_DURATION_MINUTES,
            message = "Must be between 1 and " + SeriesSchedule.MAX_DURATION_MINUTES + " minutes.")
    private Integer durationMinutes;

    @Schema(description = "Required. The first day the series runs (yyyy-MM-dd).", example = "2026-12-04",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "startDate is required")
    private LocalDate startDate;

    @Schema(nullable = true, description = "Optional. The last day the series runs (yyyy-MM-dd), inclusive. Leave "
            + "it out for a series with no end.", example = "2027-03-26")
    private LocalDate endDate;

    @Schema(nullable = true, description = "Optional. Days the series does not run (yyyy-MM-dd), at most 366.",
            example = "[\"2026-12-25\"]")
    private List<String> skipDates;

    @Schema(nullable = true, description = "Optional. One-off starts besides the schedule (yyyy-MM-ddTHH:mm), at "
            + "most 100, not on a skipped day. A new one must be in the future; one the series already has may have "
            + "passed.", example = "[\"2026-12-24T15:00\"]")
    private List<String> extraStarts;

    @Schema(nullable = true, description = "Optional, default 60. How many days ahead dates exist and can be "
            + "booked, 1 to 180.", example = "60")
    @Min(value = 1, message = "Must be between 1 and " + SeriesSchedule.MAX_BOOKING_WINDOW_DAYS + " days.")
    @Max(value = SeriesSchedule.MAX_BOOKING_WINDOW_DAYS,
            message = "Must be between 1 and " + SeriesSchedule.MAX_BOOKING_WINDOW_DAYS + " days.")
    private Integer bookingWindowDays;

    @Schema(nullable = true, description = "Optional. Sales for each NEW date open this many minutes before it "
            + "starts. Leave it out to put a date on sale as soon as it exists.", example = "20160")
    @Min(value = 1, message = "Must be at least 1 minute.")
    @Max(value = SeriesSchedule.MAX_SALES_MINUTES, message = "At most " + SeriesSchedule.MAX_SALES_MINUTES
            + " minutes (180 days).")
    private Integer salesOpenMinutesBefore;

    @Schema(nullable = true, description = "Optional. Sales for each NEW date close this many minutes before it "
            + "starts (0 = at the start). Must be less than salesOpenMinutesBefore when both are sent.",
            example = "30")
    @Min(value = 0, message = "Can't be negative.")
    @Max(value = SeriesSchedule.MAX_SALES_MINUTES, message = "At most " + SeriesSchedule.MAX_SALES_MINUTES
            + " minutes (180 days).")
    private Integer salesCloseMinutesBefore;
}
