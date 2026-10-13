package com.innbucks.bookingservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counters for the booking stock paths, exported at {@code /actuator/prometheus}.
 * Every series is registered at 0 when the service starts: Prometheus'
 * {@code increase()} cannot see a series' first sample, so a counter that
 * first appears already at 1 never fires the alert written for it.
 *
 * <ul>
 *   <li>{@code booking.claim.retry{outcome}} — a claim transaction (the
 *       {@code POST /bookings} write) that failed with a deadlock or
 *       serialization failure and was run once more. {@code succeeded}: the
 *       retry booked. {@code failed}: it hit the same failure again and the
 *       caller got a 503. {@code refused}: the retry ran and was refused for an
 *       ordinary reason (sold out), which the caller sees as usual. Expected to
 *       stay at 0 now that every path locks stock in one order.</li>
 *   <li>{@code booking.expiry.skipped} — a lapsed hold the expiry sweep tried
 *       to cancel and could not (its transaction failed and rolled back). The
 *       sweep moves on and the next run tries again; alert
 *       {@code ExpirySkipping}. A hold skipped because another request held
 *       its row is not counted.</li>
 * </ul>
 */
@Component
public class BookingMetrics {

    public static final String CLAIM_RETRY = "booking.claim.retry";
    public static final String EXPIRY_SKIPPED = "booking.expiry.skipped";

    public enum ClaimRetryOutcome {
        SUCCEEDED, FAILED, REFUSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<ClaimRetryOutcome, Counter> claimRetries = new EnumMap<>(ClaimRetryOutcome.class);
    private final Counter expirySkipped;

    public BookingMetrics(MeterRegistry registry) {
        for (ClaimRetryOutcome outcome : ClaimRetryOutcome.values()) {
            claimRetries.put(outcome, Counter.builder(CLAIM_RETRY)
                    .description("Claim transactions retried after a deadlock or serialization failure")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
        this.expirySkipped = Counter.builder(EXPIRY_SKIPPED)
                .description("Lapsed holds the expiry sweep failed to cancel and skipped")
                .register(registry);
    }

    public void claimRetry(ClaimRetryOutcome outcome) {
        claimRetries.get(outcome).increment();
    }

    public void expirySkipped() {
        expirySkipped.increment();
    }
}
