package com.innbucks.eventservice.dto;

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
            description = """
                    If provided, must be strictly **in the future** at request time (`@Future`).
                    """
    )
    @Future(message = "Event date must be in the future")
    private LocalDateTime dateTime;

    @Schema(description = "If set, must be >= 1.")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;
}
