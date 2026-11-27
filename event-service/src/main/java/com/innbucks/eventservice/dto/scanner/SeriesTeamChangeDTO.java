package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What putting a team member on (or taking them off) a whole series did
 * (TIC-72): the member as they now are, and one row per date of the series
 * that has not ended. The series' template is covered too, so the dates the
 * series makes later get the same member; it is not listed here.
 */
@Schema(name = "SeriesTeamChange")
public record SeriesTeamChangeDTO(
        @Schema(description = "The team member after the change, as GET /event-organizer/scanners/{scannerId} "
                + "returns them.")
        ScannerResponseDTO scanner,
        @Schema(example = "5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f") UUID seriesId,
        @Schema(description = "true: dates the series makes from now on get this member (assign), or no longer "
                + "do (remove).", example = "true")
        boolean futureDates,
        @Schema(description = "Each date of the series that has not ended, soonest first, and what happened to it. "
                + "Empty when the series has no such date yet.")
        List<DateOutcome> dates
) {

    /** What happened to one date. */
    public enum Outcome {
        /** Now on the date. */
        ASSIGNED,
        /** Was already on the date, on the same terms. */
        ALREADY_ASSIGNED,
        /** Sales agents: was already on the date; the ticket cap or commission changed to the series' terms. */
        TERMS_CHANGED,
        /**
         * Gate scanners: left off, because the number is already on another event
         * at an overlapping time (any organizer's, or another date of this series).
         */
        NOT_ASSIGNED_CLASH,
        /** Taken off the date. */
        REMOVED,
        /** Was not on the date. */
        NOT_ON_DATE
    }

    @Schema(name = "SeriesTeamDateOutcome")
    public record DateOutcome(
            @Schema(example = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f") UUID eventId,
            @Schema(description = "At the market offset.", example = "2026-12-04T19:00:00+02:00")
            LocalDateTime startDateTime,
            @Schema(description = "At the market offset.", example = "2026-12-04T21:00:00+02:00")
            LocalDateTime endDateTime,
            @Schema(example = "ASSIGNED") Outcome outcome
    ) {
    }
}
