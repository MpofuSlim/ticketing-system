package com.innbucks.bookingservice.config;

import com.innbucks.bookingservice.entity.Booking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every booking series an alert reads exists at 0 from startup: Prometheus'
 * {@code increase()} cannot see a series' first sample, so one that first
 * appears already at 1 never fires {@code ResurrectFailed} or
 * {@code HoldExtensionRefused}.
 */
class BookingMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingMetrics metrics = new BookingMetrics(registry);

    @Test
    void everyResurrectSeries_forEveryResurrectableReason_isRegisteredAtZero() {
        for (String reason : List.of("expired", "customer_cancelled", "buyer_released")) {
            for (String outcome : List.of("succeeded", "failed")) {
                assertThat(resurrected(reason, outcome)).as("%s/%s", reason, outcome).isZero();
            }
        }
        // An admin reversal is never resurrected, so it has no series to move.
        assertThat(registry.find(BookingMetrics.CONFIRM_RESURRECTED).tag("reason", "admin_reversed").counters())
                .isEmpty();
        assertThat(registry.find(BookingMetrics.CONFIRM_RESURRECTED).counters()).hasSize(6);
    }

    @Test
    void bothHoldRefusalSeries_areRegisteredAtZero() {
        for (String reason : List.of("max_total", "max_per_call")) {
            assertThat(registry.get(BookingMetrics.HOLD_EXTENSION_REFUSED).tag("reason", reason).counter().count())
                    .as(reason).isZero();
        }
    }

    @Test
    void eachCallMovesExactlyItsOwnSeries() {
        metrics.resurrected(Booking.CancelReason.EXPIRED, BookingMetrics.ResurrectOutcome.FAILED);
        metrics.resurrected(Booking.CancelReason.ADMIN_REVERSED, BookingMetrics.ResurrectOutcome.FAILED);
        metrics.holdExtensionRefused(BookingMetrics.HoldExtensionRefusal.MAX_PER_CALL);

        assertThat(resurrected("expired", "failed")).isEqualTo(1.0);
        assertThat(resurrected("expired", "succeeded")).isZero();
        assertThat(registry.find(BookingMetrics.CONFIRM_RESURRECTED).counters().stream()
                .mapToDouble(Counter::count).sum()).as("ADMIN_REVERSED is ignored, never thrown").isEqualTo(1.0);
        assertThat(registry.get(BookingMetrics.HOLD_EXTENSION_REFUSED).tag("reason", "max_per_call").counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get(BookingMetrics.HOLD_EXTENSION_REFUSED).tag("reason", "max_total").counter()
                .count()).isZero();
    }

    private double resurrected(String reason, String outcome) {
        return registry.get(BookingMetrics.CONFIRM_RESURRECTED).tag("reason", reason).tag("outcome", outcome)
                .counter().count();
    }
}
