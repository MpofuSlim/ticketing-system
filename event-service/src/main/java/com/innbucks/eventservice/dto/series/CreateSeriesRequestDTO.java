package com.innbucks.eventservice.dto.series;

import com.innbucks.eventservice.dto.CreateEventRequestDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.service.SeriesSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code POST /events/series} (V24, TIC-70): the event every date
 * copies, and the schedule that makes the dates. All times and dates are the
 * market's local wall clock, as the organizer reads them on a poster.
 */
@Data
@Schema(name = "CreateSeriesRequest", description = """
        A recurring event. The event fields are the ones POST /events takes (no times, no banner: the times come
        from the schedule, and the banner is uploaded to the template afterwards). Every date, time and day is
        the market's local wall clock (Africa/Harare on the ZW cell): send what the organizer typed, never UTC.""")
public class CreateSeriesRequestDTO {

    // ---- The event every date copies --------------------------------------

    @Schema(example = "Friday Comedy Night", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @Schema(description = "Optional longer description.", example = "Stand-up every Friday at the Theatre in the Park.")
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    @Schema(description = "Optional settlement code, as on POST /events; derived from the title when left out.",
            example = "COMEDYFRI")
    @Pattern(regexp = "[A-Za-z0-9]{3,12}", message = "Settlement code must be 3-12 letters or digits")
    private String settlementCode;

    @Schema(example = "Theatre in the Park, Harare", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Venue is required")
    @Size(max = 255, message = "Venue must be at most 255 characters")
    private String venue;

    @Schema(example = "COMEDY", description = "A `code` from GET /events/categories.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Category is required")
    private EventCategory category;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Location is required")
    @Valid
    private LocationDTO location;

    @Schema(description = "Each date's capacity.", example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Total capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    @Schema(nullable = true, description = "Optional terms and conditions, as on POST /events. Each date gets them "
            + "as its version 1.")
    @Size(max = CreateEventRequestDTO.TERMS_MAX_LENGTH, message = CreateEventRequestDTO.TERMS_TOO_LONG_MESSAGE)
    private String termsText;

    // ---- The schedule -----------------------------------------------------

    @Schema(description = "The days of the week the series runs: MONDAY to SUNDAY. At least one.",
            example = "[\"FRIDAY\",\"SATURDAY\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> daysOfWeek;

    @Schema(description = "Each day's start times, 24-hour HH:mm, market-local. 1 to 10.",
            example = "[\"19:00\",\"21:30\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> startTimes;

    @Schema(description = "How long each date lasts, in minutes (1 to 1440). Its end is its start plus this.",
            example = "120", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "durationMinutes is required")
    @Min(value = 1, message = "Must be between 1 and " + SeriesSchedule.MAX_DURATION_MINUTES + " minutes.")
    @Max(value = SeriesSchedule.MAX_DURATION_MINUTES,
            message = "Must be between 1 and " + SeriesSchedule.MAX_DURATION_MINUTES + " minutes.")
    private Integer durationMinutes;

    @Schema(description = "The first day the series runs (yyyy-MM-dd).", example = "2026-12-04",
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
            + "most 100, each in the future and not on a skipped day.", example = "[\"2026-12-24T15:00\"]")
    private List<String> extraStarts;

    @Schema(nullable = true, description = "Optional, default 60. How many days ahead dates exist and can be "
            + "booked, 1 to 180. The window moves forward every day.", example = "60")
    @Min(value = 1, message = "Must be between 1 and " + SeriesSchedule.MAX_BOOKING_WINDOW_DAYS + " days.")
    @Max(value = SeriesSchedule.MAX_BOOKING_WINDOW_DAYS,
            message = "Must be between 1 and " + SeriesSchedule.MAX_BOOKING_WINDOW_DAYS + " days.")
    private Integer bookingWindowDays;

    @Schema(nullable = true, description = "Optional. Ticket sales for each date open this many minutes before it "
            + "starts. Leave it out to put a date on sale as soon as it exists.", example = "20160")
    @Min(value = 1, message = "Must be at least 1 minute.")
    @Max(value = SeriesSchedule.MAX_SALES_MINUTES, message = "At most " + SeriesSchedule.MAX_SALES_MINUTES
            + " minutes (180 days).")
    private Integer salesOpenMinutesBefore;

    @Schema(nullable = true, description = "Optional. Ticket sales for each date close this many minutes before it "
            + "starts (0 = at the start). Leave it out to sell until the date ends. Must be less than "
            + "salesOpenMinutesBefore when both are sent.", example = "30")
    @Min(value = 0, message = "Can't be negative.")
    @Max(value = SeriesSchedule.MAX_SALES_MINUTES, message = "At most " + SeriesSchedule.MAX_SALES_MINUTES
            + " minutes (180 days).")
    private Integer salesCloseMinutesBefore;
}
