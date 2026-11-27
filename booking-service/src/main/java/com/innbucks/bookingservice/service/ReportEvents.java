package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which events an organizer report covers: every event in scope, one event, or
 * every date of one series (TIC-72). A series is read as the organizer's
 * bookings in the window with the series' dates kept, so the report queries
 * stay the same and the organizer scope still bounds the fetch.
 *
 * @param eventId        the one event, or null
 * @param seriesId       the series, or null
 * @param seriesEventIds the series' dates; empty unless {@code seriesId} is set
 */
public record ReportEvents(UUID eventId, UUID seriesId, Set<UUID> seriesEventIds) {

    public ReportEvents {
        seriesEventIds = seriesEventIds == null ? Set.of() : Set.copyOf(seriesEventIds);
    }

    public static ReportEvents all() {
        return new ReportEvents(null, null, Set.of());
    }

    /** One event, or every event when {@code eventId} is null. */
    public static ReportEvents event(UUID eventId) {
        return new ReportEvents(eventId, null, Set.of());
    }

    public static ReportEvents series(UUID seriesId, Set<UUID> dateIds) {
        return new ReportEvents(null, seriesId, dateIds);
    }

    public boolean isSeries() {
        return seriesId != null;
    }

    /** The event id the repository filters on: a series asks for every event, then keeps its dates. */
    public UUID queryEventId() {
        return isSeries() ? null : eventId;
    }

    /** {@code bookings} with only the series' dates kept, or unchanged when this is not a series. */
    public List<Booking> keep(List<Booking> bookings) {
        if (!isSeries()) {
            return bookings;
        }
        return bookings.stream().filter(b -> seriesEventIds.contains(b.getEventId())).toList();
    }
}
