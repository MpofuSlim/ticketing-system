package com.innbucks.eventservice.dto.series;

import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body of {@code PUT /events/series/{seriesId}/future-dates} (TIC-71): changes
 * applied to the template and to every date that has not started. A field left
 * out is left as each date has it. At least one field.
 */
@Data
@Schema(name = "FutureDatesRequest", description = """
        Changes for the template and every date that has not started. Every field is optional; send at least one. \
        A field left out is left as each date has it.""")
public class FutureDatesRequestDTO {

    @Schema(nullable = true, description = "Optional. The new title.", example = "Friday Comedy Night: Live")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @Schema(nullable = true, description = "Optional. The new description.")
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    @Schema(nullable = true, description = "Optional. The new venue. Everyone holding a ticket for a date is told.",
            example = "Reps Theatre, Harare")
    @Size(max = 255, message = "Venue must be at most 255 characters")
    private String venue;

    @Schema(nullable = true, description = "Optional. A `code` from GET /events/categories.", example = "COMEDY")
    private EventCategory category;

    @Schema(nullable = true, description = "Optional. The venue's coordinates.")
    @Valid
    private LocationDTO location;

    @Schema(nullable = true, description = "Optional. Moves every future date that starts at one of the schedule's "
            + "start times to another time the same day, and changes the schedule to match.")
    @Valid
    private MoveStartTime moveStartTime;

    /** One of the schedule's start times, and the time it becomes. */
    @Data
    @Schema(name = "MoveStartTime")
    public static class MoveStartTime {

        @Schema(description = "Required. One of the schedule's start times, 24-hour HH:mm, market-local.",
                example = "19:00", requiredMode = Schema.RequiredMode.REQUIRED)
        private String from;

        @Schema(description = "Required. The new start time, 24-hour HH:mm, market-local; not one the schedule "
                + "already has.", example = "19:30", requiredMode = Schema.RequiredMode.REQUIRED)
        private String to;
    }
}
