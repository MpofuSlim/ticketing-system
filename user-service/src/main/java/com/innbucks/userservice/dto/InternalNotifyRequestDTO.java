package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body for the service-to-service {@code POST /users/internal/{userUuid}/notify}
 * endpoint: the calling backend supplies the copy, user-service resolves the
 * user's channels and sends it (email → WhatsApp) AND records it as an in-app
 * notification. Validated manually in the controller (after the
 * X-Internal-Token check) so auth stays fail-closed-first.
 *
 * <p><b>Everything past {@code message} is optional and additive.</b> Producers
 * that already call this endpoint — marketplace restock alerts, event-service
 * approvals — keep working untouched and start populating the bell immediately,
 * as {@code GENERAL}/{@code INFO} with no deep link. Filling the extra fields
 * makes a notification actionable rather than merely visible; it is not
 * required to be delivered.
 */
@Schema(name = "InternalNotifyRequest",
        description = "Subject + message for a user notification, plus optional metadata that makes "
                + "the in-app copy actionable.")
public record InternalNotifyRequestDTO(
        @Schema(example = "Your event has been approved") String subject,
        @Schema(example = "Your event \"Summer Concert\" has been approved and is ready to publish on InnBucks.")
        String message,

        @Schema(description = "A NotificationType value. Unknown values are stored and served as-is — "
                + "a producer in another repo must never lose a notification because this service "
                + "does not yet know the name. Defaults to GENERAL.",
                nullable = true, example = "EVENT_APPROVED")
        String type,

        @Schema(description = "INFO (default), SUCCESS, WARNING or ERROR. An unrecognised value "
                + "falls back to INFO rather than refusing the notification.",
                nullable = true, example = "SUCCESS")
        String severity,

        @Schema(nullable = true, example = "42") String actorId,
        @Schema(nullable = true, example = "Alice Moyo") String actorName,

        @Schema(description = "What the notification is about, so the console can de-duplicate and "
                + "refresh one screen.", nullable = true, example = "EVENT")
        String subjectKind,
        @Schema(nullable = true, example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String subjectId,

        @Schema(description = "Where the console should send the user. Supplied by the producer so "
                + "there is no client-side type-to-route map to go stale.",
                nullable = true, example = "/ticketing/events/3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String deepLink
) {
}
