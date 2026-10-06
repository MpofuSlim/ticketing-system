package com.innbucks.bookingservice.cache;

import com.innbucks.bookingservice.dto.EventLookupDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A per-event cache of event-service's internal event lookup, for the two
 * {@code BookingService} reads that need only the event's static fields:
 * capturing the organizer uuid at {@code POST /bookings}, and labelling the
 * public ticket list (title, venue, start/end).
 *
 * <p><b>What it may serve, and what it must never serve.</b> The organizer
 * uuid ({@code tenantUserUuid}) is written once when the event is created and
 * never changed, so a cached copy is the same answer. Title, venue and
 * start/end can be edited in event-service, which cannot evict this cache: a
 * customer's ticket list can show the previous title/time, and classify a
 * ticket UPCOMING/LIVE/PAST on the previous dates, for up to the TTL
 * ({@code bookings.cache.event-lookups.ttl}, 60s). It is NOT used for event
 * ownership checks (those call event-service live and fail closed), for a
 * category's price or capacity (seat-service, live, on every booking), for the
 * scan path, or for reminders.
 *
 * <p>Only a real answer is cached: a failed or empty lookup, and an event that
 * reports no organizer uuid (a pre-backfill row), are returned uncached.
 */
@Component
public class EventLookupCache {

    private final ReadThroughCache cache;

    @Autowired
    public EventLookupCache(@Qualifier(ReadCacheConfig.READ_CACHE_MANAGER) CacheManager cacheManager) {
        this.cache = ReadThroughCache.of(cacheManager, ReadCacheConfig.EVENT_LOOKUPS);
    }

    /** The lookup for {@code eventId}: a fresh copy of a cached answer, or
     *  {@code loader}'s answer (remembered only when it is a real one). */
    public EventLookupDTO get(UUID eventId, Supplier<EventLookupDTO> loader) {
        if (eventId == null) {
            return loader.get();
        }
        Cache.ValueWrapper hit = cache.lookup(eventId);
        if (hit != null && hit.get() instanceof EventLookup cached) {
            return cached.toDto();
        }
        EventLookupDTO fresh = loader.get();
        if (fresh != null && fresh.getTenantUserUuid() != null && eventId.equals(fresh.getEventId())) {
            cache.put(eventId, EventLookup.of(fresh));
        }
        return fresh;
    }

    /** The cached form: immutable, rebuilt into a new DTO on every read. */
    record EventLookup(UUID eventId, UUID tenantUserUuid, String title,
                       LocalDateTime startDateTime, LocalDateTime endDateTime, String venue) {

        static EventLookup of(EventLookupDTO dto) {
            return new EventLookup(dto.getEventId(), dto.getTenantUserUuid(), dto.getTitle(),
                    dto.getStartDateTime(), dto.getEndDateTime(), dto.getVenue());
        }

        EventLookupDTO toDto() {
            return EventLookupDTO.builder()
                    .eventId(eventId)
                    .tenantUserUuid(tenantUserUuid)
                    .title(title)
                    .startDateTime(startDateTime)
                    .endDateTime(endDateTime)
                    .venue(venue)
                    .build();
        }
    }
}
