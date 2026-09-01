package com.innbucks.bookingservice.dto;

import java.time.LocalDateTime;

/**
 * Where a ticket's event sits relative to now — the PAST / PRESENT / FUTURE
 * split behind the customer's "my tickets" screen.
 *
 * <p><b>Classified server-side, in UTC.</b> Event times are stored as
 * zone-less {@code LocalDateTime} holding UTC and go out on the wire with an
 * explicit {@code Z} (see CLAUDE.md). Leaving the comparison to the browser
 * would make the bucket depend on the device clock and timezone — a phone set
 * an hour fast would file a live event under PAST and hide the QR the customer
 * is standing at the gate trying to show. So the server decides, and the
 * response carries the answer.
 *
 * <p><b>Unknown is optimistic.</b> Every rule below errs toward keeping a paid
 * ticket VISIBLE on the default (upcoming) screen. A customer losing sight of a
 * ticket they paid for because event-service was briefly unreachable is a far
 * worse failure than showing one entry too many.
 */
public enum TicketWindow {

    /** The event has not started yet. Also the answer when the event is unknown. */
    UPCOMING,

    /** The event is running right now — {@code start <= now <= end}, both inclusive. */
    LIVE,

    /** The event has finished. Only ever returned when the end time is KNOWN. */
    PAST;

    /**
     * Classify an event by its start/end against {@code now}. All three
     * arguments are UTC.
     *
     * <p>Null handling, and why each falls the way it does:
     * <ul>
     *   <li><b>{@code start == null}</b> — the event could not be resolved (a
     *       Feign fallback returns a null DTO when event-service is down or the
     *       circuit is open). We know nothing, so the ticket stays
     *       {@code UPCOMING} and visible rather than being filed under PAST
     *       where the customer would never look for it.</li>
     *   <li><b>{@code end == null}</b> — never {@code PAST}. The column is
     *       {@code nullable = false} upstream, so this only happens on a
     *       degraded read; and absence of an end time is not evidence the event
     *       is over. A started-but-unbounded event reads {@code LIVE}, which
     *       keeps the QR on the screen.</li>
     * </ul>
     */
    public static TicketWindow classify(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (start == null) {
            return UPCOMING;
        }
        if (now.isBefore(start)) {
            return UPCOMING;
        }
        // now >= start from here.
        if (end == null) {
            return LIVE;
        }
        return now.isAfter(end) ? PAST : LIVE;
    }
}
