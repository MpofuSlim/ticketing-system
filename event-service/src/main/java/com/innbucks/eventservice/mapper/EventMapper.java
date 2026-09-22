package com.innbucks.eventservice.mapper;

import com.innbucks.eventservice.dto.EventResponseDTO;
import com.innbucks.eventservice.dto.LocationDTO;
import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.Location;
import org.springframework.stereotype.Component;

@Component
public class EventMapper {

    public EventResponseDTO toDTO(Event event) {
        return EventResponseDTO.builder()
                .eventId(event.getEventId())
                .tenantUserUuid(event.getTenantUserUuid())
                .title(event.getTitle())
                .settlementCode(event.getSettlementCode())
                .description(event.getDescription())
                .venue(event.getVenue())
                .country(event.getCountry())
                .category(event.getCategory())
                .location(toLocationDTO(event.getLocation()))
                .bannerUrl(bannerUrl(event))
                .startDateTime(event.getStartDateTime())
                .endDateTime(event.getEndDateTime())
                .totalCapacity(event.getTotalCapacity())
                .availableTickets(event.getAvailableTickets())
                .active(event.isActive())
                .rejected(event.isRejected())
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }

    /**
     * The banner's URL, carrying a {@code ?v=} version derived from the event's
     * own {@code updatedAt}. Null when the event has no banner — that null is
     * the FE's signal to render its placeholder.
     *
     * <p><b>The version exists because the PATH cannot change.</b> The bytes are
     * served {@code Cache-Control: public, max-age=3600} (deliberately — the
     * storefront shows these posters to every visitor), and the path is
     * {@code /events/{id}/banner} whatever image is stored behind it. So after a
     * replace, every client that already had the old bytes kept showing them for
     * up to an hour, and the organizer who just uploaded a new poster saw the old
     * one and reported the upload as broken. Observed on the ZW cell 2026-09-22:
     * a successful replace showed the NEW banner on a freshly-loaded storefront
     * and the OLD one in the admin tab that had cached it.
     *
     * <p>Versioning the URL server-side fixes it for every client at once. The
     * alternative — telling each client to append its own cache-buster — is one
     * more thing every screen rendering a banner has to remember forever, and
     * silently wrong when one forgets.
     *
     * <p><b>{@code updatedAt}, not the banner's own timestamp</b>, so this needed
     * no migration. The cost is that editing a title or capacity also bumps it,
     * making clients re-fetch an unchanged poster once. That is deliberate and
     * cheap: events are edited rarely, a redundant fetch costs one image, and a
     * STALE poster costs a support conversation. Keying it exactly would mean a
     * {@code banner_updated_at} column; revisit only if banner traffic ever makes
     * the redundant fetches matter.
     *
     * <p>A null {@code updatedAt} (a row written outside JPA's lifecycle
     * callbacks) falls back to {@code createdAt}, and then to an unversioned URL
     * — degrading to exactly the old behaviour rather than emitting {@code ?v=null}.
     */
    private String bannerUrl(Event event) {
        if (event.getBannerContentType() == null || event.getBannerContentType().isBlank()) {
            return null;
        }
        String path = "/events/" + event.getEventId() + "/banner";
        java.time.LocalDateTime stamp = event.getUpdatedAt() != null
                ? event.getUpdatedAt()
                : event.getCreatedAt();
        if (stamp == null) {
            return path;
        }
        // MILLIS, not seconds: two replaces inside the same second would render an
        // identical URL and the second one would be served from cache — the exact
        // bug this method exists to prevent, just narrower. Postgres keeps
        // microsecond precision on the column, so the millis survive a round-trip.
        return path + "?v=" + stamp.toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
    }

    private LocationDTO toLocationDTO(Location location) {
        if (location == null || (location.getLatitude() == null && location.getLongitude() == null)) {
            return null;
        }
        return LocationDTO.builder()
                .latitude(location.getLatitude())
                .longitude(location.getLongitude())
                .build();
    }
}
