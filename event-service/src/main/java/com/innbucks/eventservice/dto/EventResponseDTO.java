package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventResponse", description = "Public event representation returned by this service.")
public class EventResponseDTO {

    @Schema(description = "Stable identifier for the event.")
    private UUID eventId;

    @Schema(example = "1", description = "1-based position of this event within the current /events/by-country response page. Not stored; null on other endpoints.")
    private Integer eventNo;

    @Schema(example = "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", nullable = true,
            description = "Stable cross-service organizer identifier — equal to the owning " +
                          "organizer's `user_uuid`. The single owning-organizer pointer (the " +
                          "legacy email-based `tenantId` field was removed in V7). Null only on " +
                          "pre-V6 rows that the backfill runner hasn't caught up on yet.")
    private UUID tenantUserUuid;

    @Schema(description = "Owning organizer's business details (name, email, address), resolved from " +
            "user-service. Null when the organizer has no business profile or user-service is unavailable.")
    private OrganizerDTO organizer;

    @Schema(example = "Summer Concert")
    private String title;

    @Schema(description = "Optional longer description.")
    private String description;

    @Schema(example = "Harare Gardens")
    private String venue;

    @Schema(example = "Zimbabwe", description = "Country the event belongs to, derived from the organizer's JWT at creation.")
    private String country;

    @Schema(example = "CONCERT", description = "Category of the event.")
    private EventCategory category;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    private LocationDTO location;

    @Schema(
            example = "/events/3fa85f64-5717-4562-b3fc-2c963f66afa6/banner",
            description = "Relative URL to fetch the banner image (null if no banner uploaded)."
    )
    private String bannerUrl;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = "Event start timestamp (`yyyy-MM-ddTHH:mm:ss`) — full ISO-8601 datetime including the start time."
    )
    private LocalDateTime startDateTime;

    @Schema(
            example = "2026-06-15T22:00:00",
            description = "Event end timestamp (`yyyy-MM-ddTHH:mm:ss`) — full ISO-8601 datetime including the end time."
    )
    private LocalDateTime endDateTime;

    @Schema(description = "Maximum venue capacity.")
    private Integer totalCapacity;

    @Schema(description = "Tickets still available for sale.")
    private Integer availableTickets;

    @Schema(description = "Whether the event is currently active (visible in /events/active and bookable).")
    private boolean active;

    @Schema(description = "Whether an admin has rejected this event. Rejected events never appear in " +
            "/events/active, /events/search or /events/by-country, and cannot be activated until an " +
            "admin approves them.")
    private boolean rejected;

    @Schema(description = "Creation timestamp (server time).")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp (server time).")
    private LocalDateTime updatedAt;

    @Schema(description = "Seat categories configured for this event, including sections and section prices.")
    private List<EventSeatCategoryResponseDTO> seatCategories;
}
