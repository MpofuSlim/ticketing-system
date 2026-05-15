package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.eventservice.config.FlexibleLocalDateTimeDeserializer;
import com.innbucks.eventservice.entity.Province;
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
            example = "HRE",
            allowableValues = {"HRE", "BYO", "MID", "MNL", "MCT", "MET", "MWT", "MSV", "MTN", "MTS"},
            description = "Province code where the event is hosted."
    )
    private Province province;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    @Valid
    private LocationDTO location;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = """
                    Event start timestamp (`yyyy-MM-ddTHH:mm:ss`). If provided, must be strictly **in the future**.
                    Updates both the date and the start time. Send the full ISO-8601 datetime;
                    a date-only value (e.g. `"2026-06-15"`) is rejected.
                    """
    )
    @Future(message = "Event date must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime dateTime;

    @Schema(description = "If set, must be >= 1.")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;
}
