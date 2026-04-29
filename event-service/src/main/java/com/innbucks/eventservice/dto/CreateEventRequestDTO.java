package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.Location;
import com.innbucks.eventservice.entity.Province;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(name = "CreateEventRequest", description = "Payload for creating a new event.")
public class CreateEventRequestDTO {

    @Schema(example = "Summer Concert")
    @NotBlank(message = "Title is required")
    private String title;

    @Schema(description = "Optional longer description.")
    private String description;

    @Schema(example = "Harare Gardens")
    @NotBlank(message = "Venue is required")
    private String venue;

    @Schema(
            example = "HRE",
            allowableValues = {"HRE", "BYO", "MID", "MNL", "MCT", "MET", "MWT", "MSV", "MTN", "MTS"},
            description = "Province code where the event is hosted."
    )
    @NotNull(message = "Province is required")
    private Province province;

    @Schema(
            description = """
                    Full event schedule timestamp.

                    Must be strictly **in the future** at request time (`@Future`).
                    """
    )
    @NotNull(message = "Date and time is required")
    @Future(message = "Event date must be in the future")
    private LocalDateTime dateTime;

    @Schema(description = "Maximum venue capacity.")
    @NotNull(message = "Total capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    @Schema(description = "Geographic coordinates of the event venue.")
    @NotNull(message = "Location is required")
    @Valid
    private Location location;
}
