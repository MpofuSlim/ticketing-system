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
 *       caller got a 503. {@code refused}: the retry ran and was refused for
 *       any other reason (sold out, say), which the caller sees as usual. Expected to
 *       stay at 0 now that every path locks stock in one order. A clash of a
 *       generated reference is not counted here (see the next one), so this
 *       still reads as lock contention alone.</li>
 *   <li>{@code booking.claim.reference_collision{reference}} — a claim
 *       transaction rolled back because a reference it generated was already
 *       stored (a unique violation), and was run again with fresh references,
 *       up to three times before a 503. Counts every clash, the one that
 *       exhausts the retries included. {@code ticket}: a ticket number — at 50
 *       random bits a day this should never move, so it firing means the
 *       generator regressed; alert {@code TicketNumberCollision}.
 *       {@code confirmation}: a confirmation number (24 bits a day) — expected
 *       now and then on a big on-sale day, and retried.</li>
 *   <li>{@code booking.expiry.skipped} — a lapsed hold the expiry sweep tried
 *       to cancel and could not (its transaction failed and rolled back). The
 *       sweep moves on and the next run tries again; alert
 *       {@code ExpirySkipping}. A hold skipped because another request held
 *       its row is not counted.</li>
 * </ul>
 */
@Component
public class BookingMetrics {

    public static final String REFERENCE_COLLISION = "booking.claim.reference_collision";
    public static final String CLAIM_RETRY = "booking.claim.retry";
    public static final String EXPIRY_SKIPPED = "booking.expiry.skipped";

    /** Which generated reference clashed; the {@code reference} tag of {@link #REFERENCE_COLLISION}. */
    public enum ReferenceKind {
        TICKET, CONFIRMATION;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<ReferenceKind, Counter> referenceCollisions = new EnumMap<>(ReferenceKind.class);

    public enum ClaimRetryOutcome {
        SUCCEEDED, FAILED, REFUSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<ClaimRetryOutcome, Counter> claimRetries = new EnumMap<>(ClaimRetryOutcome.class);
    private final Counter expirySkipped;

    public BookingMetrics(MeterRegistry registry) {
        for (ReferenceKind kind : ReferenceKind.values()) {
            referenceCollisions.put(kind, Counter.builder(REFERENCE_COLLISION)
                    .description("Claim attempts rolled back because a generated reference was already stored")
                    .tag("reference", kind.tag())
                    .register(registry));
        }
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

    public void referenceCollision(ReferenceKind kind) {
        referenceCollisions.get(kind).increment();
    }

    public void claimRetry(ClaimRetryOutcome outcome) {
        claimRetries.get(outcome).increment();
    }

    public void expirySkipped() {
        expirySkipped.increment();
    }
}
