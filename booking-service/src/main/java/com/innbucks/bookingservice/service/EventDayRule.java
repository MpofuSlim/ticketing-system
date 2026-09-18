package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.MarketTimeZone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Decides whether a ticket may be redeemed <em>today</em>: a ticket scans only
 * on the market-local calendar days its event actually spans.
 *
 * <p>The window is the INCLUSIVE set of market-local days from the day
 * containing {@code startDateTime} through the day containing
 * {@code endDateTime}. Not an instant range — a whole-day rule is what "the day
 * of the event" means, it needs no invented lead/grace constants, and it is the
 * only shape that structurally cannot refuse a scan while the event is actually
 * running.
 *
 * <h2>Why market-local and not UTC</h2>
 *
 * <p>Event times are stored as zone-less {@link LocalDateTime} holding UTC (see
 * the repo's "Event times are MARKET-LOCAL on the wire in, UTC out" rule), so
 * every value here is stamped {@link ZoneOffset#UTC} and re-zoned through
 * {@link MarketTimeZone#localDay} before its date is taken. Reading
 * {@code startDateTime.toLocalDate()} directly would answer in UTC, and in a
 * +2 market that is wrong for exactly the hours that matter most: a 00:30-local
 * scan on the day after a late show is 22:30Z on the PREVIOUS day, which a UTC
 * reading calls the event's day when it is not.
 *
 * <h2>Multi-day and past-midnight events</h2>
 *
 * <p>A festival running 12–14 June allows all three days. A club night running
 * 22:00 to 01:00 spans two local days and both are allowed, so nobody is turned
 * away mid-event. When {@code endDateTime} is absent — legacy rows, and events
 * created without one — the window collapses to the start day, which is the
 * conservative reading and matches how such an event is displayed.
 *
 * <p>This class is deliberately pure and static: no Spring, no clock, no I/O.
 * The repo has no {@code Clock} abstraction anywhere, so {@code now} is a
 * parameter and the caller passes {@link Instant#now()} — which is also what
 * makes every case below testable without freezing time.
 */
public final class EventDayRule {

    private EventDayRule() {
    }

    /** What the rule concluded for one (event, instant) pair. */
    public enum Verdict {
        /** Today is one of the event's local days — proceed to the claim. */
        ON_DAY,
        /** Today is outside the event's local days — refuse, do not redeem. */
        OFF_DAY,
        /**
         * The event's dates could not be established (no event payload, or a
         * null start). NOT the same as OFF_DAY: the ticket may well be valid,
         * so the caller must surface a retryable failure rather than burn it.
         */
        UNVERIFIABLE
    }

    /**
     * Classify {@code now} against an event's stored UTC start/end.
     *
     * @param startUtc zone-less value holding UTC; null makes the result UNVERIFIABLE
     * @param endUtc   zone-less value holding UTC; null collapses the window to the start day
     * @param now      the instant being judged, normally {@code Instant.now()}
     * @param market   the cell's market timezone
     */
    public static Verdict classify(LocalDateTime startUtc, LocalDateTime endUtc,
                                   Instant now, MarketTimeZone market) {
        if (startUtc == null || now == null || market == null) {
            // No start means no window. Fail to UNVERIFIABLE rather than
            // OFF_DAY so a data gap reads as "could not decide" (retryable)
            // instead of "wrong day" (a verdict about the ticket, which would
            // be a lie).
            return Verdict.UNVERIFIABLE;
        }

        LocalDate firstDay = market.localDay(startUtc.toInstant(ZoneOffset.UTC));
        // A null end is not an error: it means a single-day event, so the
        // window is the start day alone. An end BEFORE the start is corrupt
        // data rather than a zero-length window — clamp to the start day so a
        // bad row refuses everything outside that day instead of refusing
        // every day.
        LocalDate lastDay = endUtc == null
                ? firstDay
                : market.localDay(endUtc.toInstant(ZoneOffset.UTC));
        if (lastDay.isBefore(firstDay)) {
            lastDay = firstDay;
        }

        LocalDate today = market.localDay(now);
        boolean within = !today.isBefore(firstDay) && !today.isAfter(lastDay);
        return within ? Verdict.ON_DAY : Verdict.OFF_DAY;
    }

    /**
     * The first market-local day of the event, for telling gate staff which day
     * the ticket IS for. Null when the start is unknown.
     */
    public static LocalDate firstLocalDay(LocalDateTime startUtc, MarketTimeZone market) {
        if (startUtc == null || market == null) {
            return null;
        }
        return market.localDay(startUtc.toInstant(ZoneOffset.UTC));
    }
}
