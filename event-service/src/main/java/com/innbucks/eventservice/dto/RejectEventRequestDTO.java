package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Why staff are rejecting an event (V17). Sent to the organizer and kept on
 * the event until it is resubmitted or approved. Required while the review
 * step is on; optional (and the body itself optional) while it is off, so a
 * console that sends no body keeps working.
 */
@Schema(name = "RejectEventRequest")
public record RejectEventRequestDTO(
        @Schema(example = "The banner shows another organizer's logo. Please upload your own artwork.",
                maxLength = 1000, nullable = true,
                description = "What the organizer should change. Plain text: markup is stripped.")
        @Size(max = 1000, message = "Keep the note under 1000 characters.")
        String reason) {
}
