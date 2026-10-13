package com.innbucks.bookingservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import com.innbucks.bookingservice.entity.Booking;

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
 *   <li>{@code booking.hold.extension_refused{reason}} — an extend-hold that
 *       was refused (409, so payment-service mints nothing) because the hold
 *       it asked for was too long. {@code max_total}: past
 *       {@code app.booking.max-total-hold} from the booking's creation, the
 *       cap that stops a hold being renewed for ever without paying.
 *       {@code max_per_call}: more than {@code app.booking.max-hold-extension}
 *       from now, above every rail's instrument; alert
 *       {@code HoldExtensionRefused}, because it means a rail's TTL was raised
 *       without raising this bound and that rail is now refusing payments.</li>
 *   <li>{@code booking.confirm.resurrected{reason,outcome}} — a payment
 *       confirmed a CANCELLED booking whose cancel reason allows it to come
 *       back ({@code reason}: {@code expired}, {@code customer_cancelled},
 *       {@code buyer_released}). {@code succeeded}: it is CONFIRMED again.
 *       {@code failed}: its tickets could not be re-claimed (sold out, the
 *       category deleted, or seat-service unreachable), so the buyer has paid
 *       with no ticket for now; payment-service retries every minute. Alert
 *       {@code ResurrectFailed}.</li>
 * </ul>
 */
@Component
public class BookingMetrics {

    public static final String CLAIM_RETRY = "booking.claim.retry";
    public static final String EXPIRY_SKIPPED = "booking.expiry.skipped";
    public static final String HOLD_EXTENSION_REFUSED = "booking.hold.extension_refused";
    public static final String CONFIRM_RESURRECTED = "booking.confirm.resurrected";

    public enum ClaimRetryOutcome {
        SUCCEEDED, FAILED, REFUSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum HoldExtensionRefusal {
        MAX_TOTAL, MAX_PER_CALL;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum ResurrectOutcome {
        SUCCEEDED, FAILED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<ClaimRetryOutcome, Counter> claimRetries = new EnumMap<>(ClaimRetryOutcome.class);
    private final Counter expirySkipped;
    private final Map<HoldExtensionRefusal, Counter> holdExtensionRefusals = new EnumMap<>(HoldExtensionRefusal.class);
    private final Map<Booking.CancelReason, Map<ResurrectOutcome, Counter>> resurrections =
            new EnumMap<>(Booking.CancelReason.class);

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
        for (HoldExtensionRefusal reason : HoldExtensionRefusal.values()) {
            holdExtensionRefusals.put(reason, Counter.builder(HOLD_EXTENSION_REFUSED)
                    .description("Hold extensions refused because the requested hold was past a cap")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
        // Only the reasons a resurrection can be attempted for: a series that
        // can never move would only be noise on the dashboard.
        for (Booking.CancelReason reason : Booking.CancelReason.values()) {
            if (!reason.resurrectable()) {
                continue;
            }
            Map<ResurrectOutcome, Counter> byOutcome = new EnumMap<>(ResurrectOutcome.class);
            for (ResurrectOutcome outcome : ResurrectOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(CONFIRM_RESURRECTED)
                        .description("Cancelled bookings a late payment tried to bring back")
                        .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                        .tag("outcome", outcome.tag())
                        .register(registry));
            }
            resurrections.put(reason, byOutcome);
        }
    }

    public void claimRetry(ClaimRetryOutcome outcome) {
        claimRetries.get(outcome).increment();
    }

    public void expirySkipped() {
        expirySkipped.increment();
    }

    public void holdExtensionRefused(HoldExtensionRefusal reason) {
        holdExtensionRefusals.get(reason).increment();
    }

    /** {@code reason} must be resurrectable; anything else is ignored, never thrown. */
    public void resurrected(Booking.CancelReason reason, ResurrectOutcome outcome) {
        Map<ResurrectOutcome, Counter> byOutcome = resurrections.get(reason);
        if (byOutcome != null) {
            byOutcome.get(outcome).increment();
        }
    }
}
