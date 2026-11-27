package com.innbucks.eventservice.dto.series;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a change to a series did (TIC-71): the series as it now is, and what
 * happened to each date the change touched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SeriesChange")
public class SeriesChangeDTO {

    @Schema(description = "The series after the change, as GET /events/series/{seriesId} returns it.")
    private EventSeriesResponseDTO series;

    @Schema(description = "Each date the change touched, soonest first. Empty when it touched none.")
    private List<DateChange> dates;

    /** What happened to one date. */
    public enum Outcome {
        /** Removed: nobody held a ticket for it. */
        REMOVED,
        /** Kept because people hold tickets for it; it goes ahead as before. */
        KEPT_HAS_BOOKINGS,
        /** Its details changed. */
        UPDATED,
        /** Moved to its new start time (and its details changed, if any were sent). */
        MOVED,
        /** Not moved: the series already has a date at the new time. */
        NOT_MOVED_TAKEN,
        /** Not moved: the new time that day has already passed. */
        NOT_MOVED_PASSED,
        /** Sales closed by a pause. */
        SALES_PAUSED,
        /** Sales opened again by a resume. */
        SALES_RESUMED,
        /** The ticket type's price changed. */
        PRICE_UPDATED,
        /** The ticket type already had that price. */
        PRICE_UNCHANGED,
        /** The date has no ticket type of that name. */
        NO_SUCH_TICKET_TYPE,
        /** A price tier still open on the date is at or above the new price; {@code message} says which. */
        PRICE_REFUSED,
        /** Unchanged: the date has its own banner or description, so it kept it ({@code keptOwn} says which). */
        KEPT_OWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "SeriesDateChange")
    public static class DateChange {

        @Schema(example = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
        private UUID eventId;

        @Schema(description = "true for the series' template (a price change reaches it too); absent otherwise.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean template;

        @Schema(description = "The date's start after the change, at the market offset.",
                example = "2026-12-04T19:30:00+02:00")
        private LocalDateTime startDateTime;

        @Schema(example = "MOVED")
        private Outcome outcome;

        @Schema(description = "How many active bookings the date has, when that was asked; absent otherwise.",
                example = "3")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Long activeBookings;

        @Schema(description = "What the date kept of its own instead of taking the series' new value (banner, "
                + "description), because it was given its own on that date. Absent when it kept nothing.",
                example = "[\"description\"]")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> keptOwn;

        @Schema(description = "Why, when the outcome needs a reason; absent otherwise.")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String message;
    }
}
