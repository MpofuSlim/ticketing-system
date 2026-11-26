package com.innbucks.seatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Body of {@code POST /seat-categories/internal/copy}: copy one event's seat
 * layout onto another. Sent by event-service's series generator, which copies
 * a series' template event onto each new date.
 *
 * <p>No bean-validation annotations, on purpose: the controller checks the
 * internal token first and only then the fields, so a caller without the token
 * never learns which field was wrong.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "CopyLayoutRequest",
        description = "Copy the source event's live seat categories (with their sections, price tiers still "
                + "open and add-ons) onto the target event. Internal: X-Internal-Token.")
public class CopyLayoutRequestDTO {

    @Schema(description = "The event whose layout is copied (a series' template).",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID sourceEventId;

    @Schema(description = "The event that receives the copy (a new series date).",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "9b2c7e10-4d5f-4a8b-9c1d-2e3f4a5b6c7d")
    private UUID targetEventId;

    @Schema(description = "Optional. The target event's capacity: a layout with more seats is refused (409) and "
            + "nothing is copied, so a copy can never oversell the target.", example = "60")
    private Integer maxTotalSeats;
}
