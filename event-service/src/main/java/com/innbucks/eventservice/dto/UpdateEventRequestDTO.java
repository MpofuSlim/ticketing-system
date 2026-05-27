package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.eventservice.config.FlexibleLocalDateTimeDeserializer;
import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(
        name = "UpdateEventRequest",
        description = """
                Partial update payload. Omitted fields are left unchanged on the server.

                The event's `country` cannot be changed here — it is tied to the organizer's JWT.

                If `totalCapacity` is provided, `availableTickets` is adjusted by the difference in capacity.
                """
)
public class UpdateEventRequestDTO {

    @Schema(example = "Summer Concert (Updated)")
    private String title;

    @Schema(description = "Optional longer description.")
    private String description;

    @Schema(example = "Harare Gardens")
    private String venue;

    @Schema(
            example = "CONCERT",
            allowableValues = {"BOOKS", "COMEDY", "HALF_MARATHON", "MARATHON", "CONCERT", "SPORT"},
            description = "Category of the event."
    )
    private EventCategory category;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    @Valid
    private LocationDTO location;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = """
                    Event start timestamp (`yyyy-MM-ddTHH:mm:ss`). If provided, must be strictly **in the future**.
                    Send the full ISO-8601 datetime; a date-only value (e.g. `"2026-06-15"`) is rejected.
                    """
    )
    @Future(message = "Event start must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime startDateTime;

    @Schema(
            example = "2026-06-15T22:00:00",
            description = """
                    Event end timestamp (`yyyy-MM-ddTHH:mm:ss`). If provided, must be strictly **after** the start.
                    """
    )
    @Future(message = "Event end must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime endDateTime;

    @Schema(description = "If set, must be >= 1.")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    // Only validates when both ends are supplied in the same request; the
    // service applies a further guard against the merged (stored) values when
    // only one side is updated.
    @AssertTrue(message = "endDateTime must be after startDateTime")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isEndAfterStart() {
        return startDateTime == null || endDateTime == null || endDateTime.isAfter(startDateTime);
    }
}
