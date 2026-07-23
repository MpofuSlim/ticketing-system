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
@Schema(name = "CreateEventRequest", description = "Payload for creating a new event. The event's country is not "
        + "supplied here — it is derived from the authenticated organizer's JWT `country` claim.")
public class CreateEventRequestDTO {

    @Schema(example = "Summer Concert")
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @Schema(description = "Optional longer description.")
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    @Schema(example = "Harare Gardens")
    @NotBlank(message = "Venue is required")
    @Size(max = 255, message = "Venue must be at most 255 characters")
    private String venue;

    @Schema(
            example = "CONCERT",
            allowableValues = {"BOOKS", "COMEDY", "FUN_RUN", "HALF_MARATHON", "MARATHON", "CONCERT", "SPORT"},
            description = "Category of the event."
    )
    @NotNull(message = "Category is required")
    private EventCategory category;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    @NotNull(message = "Location is required")
    @Valid
    private LocationDTO location;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = """
                    Event start timestamp (`yyyy-MM-ddTHH:mm:ss`). Must be strictly **in the future**.
                    Send the full ISO-8601 datetime including the time portion (e.g. `"2026-06-15T19:00:00"`);
                    a date-only value is rejected.
                    """
    )
    @NotNull(message = "Start date and time is required")
    @Future(message = "Event start must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime startDateTime;

    @Schema(
            example = "2026-06-15T22:00:00",
            description = """
                    Event end timestamp (`yyyy-MM-ddTHH:mm:ss`). Must be strictly **after** `startDateTime`.
                    Send the full ISO-8601 datetime including the time portion.
                    """
    )
    @NotNull(message = "End date and time is required")
    @Future(message = "Event end must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime endDateTime;

    @Schema(description = "Maximum venue capacity.")
    @NotNull(message = "Total capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    @AssertTrue(message = "endDateTime must be after startDateTime")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isEndAfterStart() {
        return startDateTime == null || endDateTime == null || endDateTime.isAfter(startDateTime);
    }
}
