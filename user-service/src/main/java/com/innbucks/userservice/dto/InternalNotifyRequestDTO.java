package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body for the service-to-service {@code POST /users/internal/{userUuid}/notify}
 * endpoint: the calling backend supplies the copy, user-service resolves the
 * user's channels and sends it (email → WhatsApp). Validated manually in the
 * controller (after the X-Internal-Token check) so auth stays fail-closed-first.
 */
@Schema(name = "InternalNotifyRequest", description = "Subject + message for a best-effort user notification.")
public record InternalNotifyRequestDTO(
        @Schema(example = "Your event has been approved") String subject,
        @Schema(example = "Your event \"Summer Concert\" has been approved and is ready to publish on InnBucks.")
        String message
) {
}
