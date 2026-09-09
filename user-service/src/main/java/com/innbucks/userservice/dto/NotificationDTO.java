package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.userservice.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** One notification as the console renders it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Notification", description = "A single in-app notification for the calling user.")
public record NotificationDTO(

        @Schema(example = "0d4f2b1a-7c3e-4a58-9b6d-2e1f8c7a4b03")
        UUID id,

        @Schema(description = "See NotificationType. A client that does not recognise a value "
                + "should render it with a default icon rather than dropping it.",
                example = "SERVICE_REQUEST_APPROVED")
        String type,

        @Schema(example = "Marketplace access approved")
        String title,

        @Schema(example = "Your request for Marketplace access was approved. Sign in again to see it.")
        String body,

        @Schema(example = "SUCCESS", allowableValues = {"INFO", "SUCCESS", "WARNING", "ERROR"})
        String severity,

        @Schema(example = "2026-09-09T08:30:00Z")
        LocalDateTime createdAt,

        @Schema(description = "Null while unread — the badge counts exactly these.",
                nullable = true, example = "2026-09-09T09:02:11Z")
        LocalDateTime readAt,

        @Schema(description = "Who caused it; absent for a system-generated notice.", nullable = true)
        Actor actor,

        @Schema(description = "What it is about, so a client can de-duplicate and refresh the "
                + "right screen instead of reloading everything.", nullable = true)
        Subject subject,

        @Schema(description = "Where to go. Supplied by the server so the console does not keep a "
                + "client-side type-to-route map that goes stale silently.", nullable = true,
                example = "/system-users/service-requests?highlight=14")
        String deepLink
) {

    @Schema(name = "NotificationActor")
    public record Actor(
            @Schema(nullable = true, example = "42") String id,
            @Schema(example = "Alice Moyo") String name) {}

    @Schema(name = "NotificationSubject")
    public record Subject(
            @Schema(example = "SERVICE_REQUEST") String kind,
            @Schema(example = "14") String id) {}

    public static NotificationDTO from(Notification n) {
        // actor and subject are omitted entirely rather than sent as objects
        // full of nulls — the console tests for their presence.
        Actor actor = (n.getActorName() == null && n.getActorId() == null)
                ? null : new Actor(n.getActorId(), n.getActorName());
        Subject subject = (n.getSubjectKind() == null)
                ? null : new Subject(n.getSubjectKind(), n.getSubjectId());
        return new NotificationDTO(
                n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getSeverity() == null ? null : n.getSeverity().name(),
                n.getCreatedAt(), n.getReadAt(), actor, subject, n.getDeepLink());
    }
}
