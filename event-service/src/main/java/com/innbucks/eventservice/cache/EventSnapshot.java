package com.innbucks.eventservice.cache;

import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.EventCategory;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An immutable copy of an event's own fields, as {@code EventMapper} renders
 * them — what a public list page caches. Never the JPA entity (it is attached
 * to nothing once cached, and lazy state would fail) and never the response
 * DTO itself (it is mutable, and every request decorates it with live
 * availability, organizer details and the stripped organizer id).
 * {@link #toDto()} hands out a fresh DTO each time.
 *
 * <p>Holds no rendered timestamp: the market offset is applied by Jackson per
 * request ({@code WireAudience}), so one snapshot serves the public and the
 * S2S audience alike.
 *
 * <p>{@code storedAvailableTickets} is the event row's own mirror — the value
 * a response falls back to only when booking-service reports no live count for
 * the event. The live count itself is never cached.
 */
public record EventSnapshot(
        UUID eventId,
        UUID tenantUserUuid,
        String title,
        String settlementCode,
        String description,
        String venue,
        String country,
        EventCategory category,
        Double latitude,
        Double longitude,
        String bannerUrl,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        Integer totalCapacity,
        Integer storedAvailableTickets,
        boolean active,
        boolean rejected,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** Null in, null out (a mocked mapper may answer null). */
    public static EventSnapshot of(EventResponseDTO dto) {
        if (dto == null) {
            return null;
        }
        LocationDTO location = dto.getLocation();
        return new EventSnapshot(
                dto.getEventId(),
                dto.getTenantUserUuid(),
                dto.getTitle(),
                dto.getSettlementCode(),
                dto.getDescription(),
                dto.getVenue(),
                dto.getCountry(),
                dto.getCategory(),
                location == null ? null : location.getLatitude(),
                location == null ? null : location.getLongitude(),
                dto.getBannerUrl(),
                dto.getStartDateTime(),
                dto.getEndDateTime(),
                dto.getTotalCapacity(),
                dto.getAvailableTickets(),
                dto.isActive(),
                dto.isRejected(),
                dto.getCreatedAt(),
                dto.getUpdatedAt());
    }

    /** A new, independent DTO carrying exactly what the mapper produced. */
    public EventResponseDTO toDto() {
        return EventResponseDTO.builder()
                .eventId(eventId)
                .tenantUserUuid(tenantUserUuid)
                .title(title)
                .settlementCode(settlementCode)
                .description(description)
                .venue(venue)
                .country(country)
                .category(category)
                .location(latitude == null && longitude == null ? null
                        : LocationDTO.builder().latitude(latitude).longitude(longitude).build())
                .bannerUrl(bannerUrl)
                .startDateTime(startDateTime)
                .endDateTime(endDateTime)
                .totalCapacity(totalCapacity)
                .availableTickets(storedAvailableTickets)
                .active(active)
                .rejected(rejected)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
