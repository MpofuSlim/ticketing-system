package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.MarketTimeZone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the scan-day window. Every value below is written as the UTC instant the
 * database actually holds, with the market-local reading spelled out in the
 * comment, because the whole point of the rule is that those two disagree.
 *
 * <p>ZW is Africa/Harare, UTC+2, no DST.
 */
class EventDayRuleTest {

    private final MarketTimeZone zw = new MarketTimeZone("ZW");

    /** A stored event time: zone-less LocalDateTime that holds UTC. */
    private static LocalDateTime utc(String iso) {
        return LocalDateTime.parse(iso);
    }

    /** The instant a scan happens at. */
    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    // ---- the core case ------------------------------------------------------

    @Test
    void scanOnTheEventsOwnLocalDay_isAllowed() {
        // Event 2026-06-19 19:00–23:00 Harare == 17:00Z–21:00Z the same day.
        LocalDateTime start = utc("2026-06-19T17:00");
        LocalDateTime end = utc("2026-06-19T21:00");

        // 18:30 Harare on the 19th.
        assertThat(EventDayRule.classify(start, end, at("2026-06-19T16:30:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
    }

    @Test
    void scanTheDayBeforeAndTheDayAfter_areRefused() {
        LocalDateTime start = utc("2026-06-19T17:00");
        LocalDateTime end = utc("2026-06-19T21:00");

        // 2026-06-18 20:00 Harare.
        assertThat(EventDayRule.classify(start, end, at("2026-06-18T18:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
        // 2026-06-20 09:00 Harare — the morning after.
        assertThat(EventDayRule.classify(start, end, at("2026-06-20T07:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    @Test
    void scanFromMidnightLocal_isAllowed_wholeDayRule() {
        // 00:01 Harare on the event's day, hours before an evening show. The
        // rule is "the day of the event", not "around the start time", so this
        // is deliberately allowed — there is no invented lead constant.
        LocalDateTime start = utc("2026-06-19T17:00");
        assertThat(EventDayRule.classify(start, utc("2026-06-19T21:00"), at("2026-06-18T22:01:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
    }

    // ---- the UTC-vs-market trap --------------------------------------------

    @Test
    void aScanJustAfterLocalMidnight_isNotTheEventsDay_evenThoughUtcStillSaysSo() {
        // THE case a UTC comparison gets wrong. Event on the 19th local.
        // The scan is 2026-06-20 00:30 Harare == 2026-06-19T22:30Z — UTC still
        // reads "the 19th", the market clock correctly reads the 20th.
        LocalDateTime start = utc("2026-06-19T17:00");
        LocalDateTime end = utc("2026-06-19T21:00");

        assertThat(EventDayRule.classify(start, end, at("2026-06-19T22:30:00Z"), zw))
                .as("00:30 local the next day must be OFF_DAY")
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    @Test
    void lateEveningStart_theEventsOwnDayIsTheLocalOne() {
        // Event starts 2026-06-19 23:00 Harare == 21:00Z the same day. A scan
        // at 23:10 local is 21:10Z — same day either way, but assert it so the
        // near-midnight start is covered.
        LocalDateTime start = utc("2026-06-19T21:00");
        assertThat(EventDayRule.classify(start, null, at("2026-06-19T21:10:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
    }

    // ---- multi-day and past-midnight events --------------------------------

    @Test
    void clubNightRunningPastMidnight_bothLocalDaysAllowed() {
        // 2026-06-19 22:00 -> 2026-06-20 01:00 Harare == 20:00Z and 23:00Z,
        // both stored on the 19th in UTC. Nobody may be refused mid-event.
        LocalDateTime start = utc("2026-06-19T20:00");
        LocalDateTime end = utc("2026-06-19T23:00");

        // 22:30 local on the 19th.
        assertThat(EventDayRule.classify(start, end, at("2026-06-19T20:30:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        // 00:30 local on the 20th — still the event, must NOT be refused.
        assertThat(EventDayRule.classify(start, end, at("2026-06-19T22:30:00Z"), zw))
                .as("a scan during the event's own run must never be refused")
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
    }

    @Test
    void multiDayFestival_everyDayInclusiveIsAllowed_includingTheMiddle() {
        // 12–14 June, local.
        LocalDateTime start = utc("2026-06-12T08:00");
        LocalDateTime end = utc("2026-06-14T20:00");

        assertThat(EventDayRule.classify(start, end, at("2026-06-12T10:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, end, at("2026-06-13T10:00:00Z"), zw))
                .as("the middle day must be allowed")
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, end, at("2026-06-14T10:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, end, at("2026-06-15T10:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    // ---- degenerate data ----------------------------------------------------

    @Test
    void nullEnd_collapsesToTheStartDay() {
        LocalDateTime start = utc("2026-06-19T17:00");
        assertThat(EventDayRule.classify(start, null, at("2026-06-19T16:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, null, at("2026-06-20T16:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    @Test
    void endBeforeStart_isClampedToTheStartDay_notAnEmptyWindow() {
        // Corrupt row. Clamping refuses everything outside the start day rather
        // than refusing every day, which would strand a real ticket.
        LocalDateTime start = utc("2026-06-19T17:00");
        LocalDateTime end = utc("2026-06-17T17:00");
        assertThat(EventDayRule.classify(start, end, at("2026-06-19T16:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, end, at("2026-06-17T16:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    @Test
    void nullStart_isUnverifiable_notOffDay() {
        // The distinction the whole fail-closed design rests on: "I don't know"
        // must never be reported as "wrong day", which is a claim about the
        // ticket and would burn a valid one.
        assertThat(EventDayRule.classify(null, utc("2026-06-19T21:00"), at("2026-06-19T16:00:00Z"), zw))
                .isEqualTo(EventDayRule.Verdict.UNVERIFIABLE);
        assertThat(EventDayRule.classify(utc("2026-06-19T17:00"), null, null, zw))
                .isEqualTo(EventDayRule.Verdict.UNVERIFIABLE);
        assertThat(EventDayRule.classify(utc("2026-06-19T17:00"), null, at("2026-06-19T16:00:00Z"), null))
                .isEqualTo(EventDayRule.Verdict.UNVERIFIABLE);
    }

    // ---- a market on a different offset -------------------------------------

    @Test
    void theRuleFollowsTheCellsMarket_notAFixedOffset() {
        // Lagos is UTC+1. An event at 23:30 Lagos on the 19th is 22:30Z the
        // same day; the same instant is 00:30 on the 20th in Harare. The two
        // cells must disagree, and each must be right about its own market.
        MarketTimeZone ng = new MarketTimeZone("NG");
        LocalDateTime start = utc("2026-06-19T21:00");
        Instant scan = at("2026-06-19T22:30:00Z");

        assertThat(EventDayRule.classify(start, null, scan, ng))
                .as("23:30 Lagos on the 19th is still the 19th")
                .isEqualTo(EventDayRule.Verdict.ON_DAY);
        assertThat(EventDayRule.classify(start, null, scan, zw))
                .as("the same instant is 00:30 on the 20th in Harare")
                .isEqualTo(EventDayRule.Verdict.OFF_DAY);
    }

    // ---- the refusal payload ------------------------------------------------

    @Test
    void firstLocalDay_isTheMarketDay_notTheUtcDay() {
        // Stored 22:30Z on the 19th == 00:30 Harare on the 20th, so the day to
        // show gate staff is the 20th.
        assertThat(EventDayRule.firstLocalDay(utc("2026-06-19T22:30"), zw))
                .isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(EventDayRule.firstLocalDay(null, zw)).isNull();
        assertThat(EventDayRule.firstLocalDay(utc("2026-06-19T22:30"), null)).isNull();
    }
}
