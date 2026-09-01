package com.innbucks.bookingservice.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PAST / PRESENT / FUTURE contract behind the customer's ticket wallet.
 *
 * <p>Pure JUnit deliberately — the classification is a total function of
 * (start, end, now), so it needs no Spring context and runs everywhere,
 * including the sandboxes without a Docker daemon (see CLAUDE.md).
 *
 * <p><b>The boundary and null cases are the point.</b> The happy path
 * ("next month's event is UPCOMING") could hardly break; what breaks is an
 * event exactly at its start instant, or one whose lookup failed — and both
 * failure modes end with a customer at a gate unable to find their QR.
 */
class TicketWindowTest {

    private static final LocalDateTime START =
            LocalDateTime.of(2026, Month.SEPTEMBER, 12, 16, 0);
    private static final LocalDateTime END =
            LocalDateTime.of(2026, Month.SEPTEMBER, 12, 22, 0);

    @Test
    @DisplayName("before the start it is UPCOMING")
    void beforeStart() {
        assertThat(TicketWindow.classify(START, END, START.minusDays(30))).isEqualTo(TicketWindow.UPCOMING);
        assertThat(TicketWindow.classify(START, END, START.minusSeconds(1))).isEqualTo(TicketWindow.UPCOMING);
    }

    @Test
    @DisplayName("between start and end it is LIVE")
    void duringEvent() {
        assertThat(TicketWindow.classify(START, END, START.plusHours(3))).isEqualTo(TicketWindow.LIVE);
    }

    @Test
    @DisplayName("after the end it is PAST")
    void afterEnd() {
        assertThat(TicketWindow.classify(START, END, END.plusSeconds(1))).isEqualTo(TicketWindow.PAST);
        assertThat(TicketWindow.classify(START, END, END.plusYears(1))).isEqualTo(TicketWindow.PAST);
    }

    @Test
    @DisplayName("BOUNDARIES: both the start and end instants are inclusive of LIVE")
    void boundariesAreInclusive() {
        // The doors-open instant must not read UPCOMING (the customer is at the
        // gate) and the closing instant must not read PAST (they are still
        // inside). Off-by-one here is invisible in a demo and infuriating live.
        assertThat(TicketWindow.classify(START, END, START))
                .as("exactly at doors-open")
                .isEqualTo(TicketWindow.LIVE);
        assertThat(TicketWindow.classify(START, END, END))
                .as("exactly at closing")
                .isEqualTo(TicketWindow.LIVE);
    }

    @Test
    @DisplayName("a zero-length event is LIVE at its single instant, PAST after")
    void zeroLengthEvent() {
        assertThat(TicketWindow.classify(START, START, START)).isEqualTo(TicketWindow.LIVE);
        assertThat(TicketWindow.classify(START, START, START.plusSeconds(1))).isEqualTo(TicketWindow.PAST);
    }

    @Test
    @DisplayName("UNKNOWN EVENT: a null start is UPCOMING, never PAST")
    void nullStartStaysVisible() {
        // event-service unreachable / circuit open -> the Feign fallback yields a
        // null DTO. The customer paid; the ticket must stay on the default screen
        // rather than being filed under PAST where they will never look for it.
        assertThat(TicketWindow.classify(null, null, LocalDateTime.now()))
                .isEqualTo(TicketWindow.UPCOMING);
        assertThat(TicketWindow.classify(null, END, END.plusYears(5)))
                .as("even with an end in the distant past, an unknown start is not PAST")
                .isEqualTo(TicketWindow.UPCOMING);
    }

    @Test
    @DisplayName("UNKNOWN END: a started event with no end is LIVE — never PAST")
    void nullEndIsNeverPast() {
        // Absence of an end time is not evidence the event is over. Reading it as
        // PAST would hide a QR that may still be needed.
        assertThat(TicketWindow.classify(START, null, START.plusHours(1))).isEqualTo(TicketWindow.LIVE);
        assertThat(TicketWindow.classify(START, null, START.plusYears(2))).isEqualTo(TicketWindow.LIVE);
        assertThat(TicketWindow.classify(START, null, START.minusHours(1)))
                .as("a null end does not affect the not-yet-started case")
                .isEqualTo(TicketWindow.UPCOMING);
    }

    @Test
    @DisplayName("PAST is only ever returned when the end time is genuinely known")
    void pastRequiresAKnownEnd() {
        // The single invariant the whole "unknown is optimistic" policy rests on:
        // nothing lands in PAST — the bucket the customer stops checking — unless
        // we positively know the event finished.
        LocalDateTime now = LocalDateTime.now();
        assertThat(TicketWindow.classify(null, null, now)).isNotEqualTo(TicketWindow.PAST);
        assertThat(TicketWindow.classify(null, END, now)).isNotEqualTo(TicketWindow.PAST);
        assertThat(TicketWindow.classify(START, null, now)).isNotEqualTo(TicketWindow.PAST);
    }
}
