package innbucks.paymentservice.order;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.EventServiceClient;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.SettlementReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@link OrderGateway} for ticket bookings — wraps the pre-generalization
 * behaviour of the checkout flow VERBATIM:
 * <ul>
 *   <li>{@link #fetch}: booking-service {@code GET /bookings/internal/{id}}
 *       for {@code totalAmount} (decimal dollars → CENTS here, the single
 *       conversion point for this product; sub-cent precision refused, never
 *       rounded) + {@code currency} (cell-currency fallback) +
 *       {@code phoneNumber} as the payer. Settlement metadata rides the
 *       best-effort event-service lookup: tag {@code <settlementCode>} (or the
 *       event-id tag), narration {@code "Ticketize <title> booking <shortId>"}
 *       — a lookup miss degrades, never blocks.</li>
 *   <li>{@link #extendHold}: the pre-mint seat-hold extension — the hold
 *       must outlive the rail's last payable instant +
 *       {@link #HOLD_SAFETY_MARGIN} (code 13, EcoCash 10, card 33 minutes
 *       with the default TTLs); a 404/409/400 refusal means the booking is
 *       dead (customer rebooks with ZERO money moved), anything else refuses
 *       503.</li>
 *   <li>{@link #confirm}: the existing idempotent
 *       {@code PATCH /bookings/internal/{id}/confirm}, mapped onto
 *       {@link ConfirmOutcome} (2xx → CONFIRMED with the confirmation number;
 *       4xx refusal → REJECTED; 5xx/unreachable → UNREACHABLE).</li>
 * </ul>
 */
@Slf4j
@Component
public class BookingOrderGateway implements OrderGateway {

    private final BookingServiceClient bookingServiceClient;
    private final EventServiceClient eventServiceClient;
    private final String cellCurrency;

    public BookingOrderGateway(
            BookingServiceClient bookingServiceClient,
            EventServiceClient eventServiceClient,
            @Value("${innbucks.currency:USD}") String cellCurrency) {
        this.bookingServiceClient = bookingServiceClient;
        this.eventServiceClient = eventServiceClient;
        this.cellCurrency = cellCurrency;
    }

    @Override
    public OrderType type() {
        return OrderType.BOOKING;
    }

    @Override
    public OrderSnapshot fetch(String orderRef) {
        UUID bookingId = parseBookingId(orderRef);
        // BookingConfirmationException (404/503/...) propagates — the
        // controllers map it onto the historical status vocabulary.
        Map<String, Object> booking = bookingServiceClient.getBooking(bookingId);
        BigDecimal amount = asBigDecimal(booking.get("totalAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentRequestException(
                    "Booking has no positive totalAmount; cannot request payment", 422);
        }
        long amountCents = toCents(amount);
        String currency = asString(booking.get("currency"));
        if (currency == null || currency.isBlank()) {
            currency = cellCurrency;
        }

        // Settlement tagging is best-effort: the event's settlementCode goes
        // into the InnBucks reference (TKZ-<CODE>-<id>) so the merchant
        // statement groups per event; the title makes the narration
        // human-readable. A failed lookup degrades to the event-id tag — it
        // never blocks the payment.
        UUID eventId = asUuid(booking.get("eventId"));
        EventServiceClient.EventSettlementInfo eventInfo =
                eventServiceClient.getSettlementInfo(eventId).orElse(null);
        String settlementTag = SettlementReference.tagOf(
                eventInfo != null ? eventInfo.settlementCode() : null, eventId);
        String narration = buildNarration(eventInfo != null ? eventInfo.title() : null, bookingId);

        return new OrderSnapshot(
                orderRef,
                amountCents,
                currency,
                asString(booking.get("phoneNumber")),
                settlementTag,
                narration,
                // The booking flow has no payable pre-check by design: the
                // pre-mint hold extension is the authoritative liveness gate
                // (it refuses expired/cancelled/confirmed bookings).
                true);
    }

    @Override
    public void extendHold(String orderRef, Instant payableUntil) {
        UUID bookingId = parseBookingId(orderRef);
        // The hold (minutes from booking) is shorter than any instrument (a
        // code lives 10 min from NOW, a card checkout 30) — without this, any
        // payment approved after the hold lapsed was money taken + confirm
        // refused (the paid-but-no-ticket gap). The hold is sized by the
        // RAIL's own last payable instant: a fixed code-sized window left a
        // card paid after minute 13 with no ticket. Extend BEFORE any ledger
        // write or mint; a refusal means the booking is already
        // expired/cancelled, so the customer rebooks with ZERO money moved.
        try {
            bookingServiceClient.extendHold(bookingId, payableUntil.plus(HOLD_SAFETY_MARGIN));
        } catch (BookingServiceClient.BookingConfirmationException e) {
            if (e.getStatusCode() == 404 || e.getStatusCode() == 409 || e.getStatusCode() == 400) {
                log.warn("[booking-gateway] hold extension refused bookingId={} status={} — payment refused pre-mint: {}",
                        bookingId, e.getStatusCode(), e.getMessage());
                throw new InvalidPaymentRequestException(
                        "Your booking has expired — please create a new booking and try again", 409);
            }
            log.warn("[booking-gateway] hold extension unreachable bookingId={} status={} — refusing payment: {}",
                    bookingId, e.getStatusCode(), e.getMessage());
            throw new InvalidPaymentRequestException(
                    "We could not secure your booking right now; please try again shortly", 503);
        }
    }

    @Override
    public ConfirmOutcome confirm(String orderRef, String confirmationRef, long amountCents) {
        UUID bookingId = parseBookingId(orderRef);
        try {
            Map<String, Object> confirmed = bookingServiceClient.confirmBooking(bookingId);
            Object confirmation = confirmed == null ? null : confirmed.get("confirmationNumber");
            return ConfirmOutcome.confirmed(confirmation == null ? null : confirmation.toString());
        } catch (BookingServiceClient.BookingConfirmationException e) {
            // The client answers 503 for connect/read failures and relays
            // booking-service's own status otherwise. 5xx-class = the confirm
            // may have landed or may succeed later → UNREACHABLE; a 4xx is a
            // definite refusal → REJECTED. Both park the row for the
            // confirm-retry sweep — never a guessed terminal state.
            if (e.getStatusCode() >= 500) {
                return ConfirmOutcome.unreachable(e.getMessage());
            }
            return ConfirmOutcome.rejected(e.getMessage());
        }
    }

    /**
     * Booking totals are decimal major units (e.g. 50.00 USD); the Merchant
     * API takes CENTS. Anything with sub-cent precision is refused rather
     * than silently rounded — a ledger must never charge an amount that
     * differs from the booking by even a fraction. This is the single
     * major→minor conversion point for the booking product.
     */
    public static long toCents(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidPaymentRequestException(
                    "Booking totalAmount has sub-cent precision; cannot request payment", 422);
        }
    }

    /**
     * Statement narration: lead with the event title (truncated — narration is
     * free text but statement columns aren't infinite) so a human scanning the
     * merchant statement sees which event the money belongs to without decoding
     * the reference. Falls back to the historical generic copy when the event
     * lookup didn't resolve a title.
     */
    static String buildNarration(String eventTitle, UUID bookingId) {
        if (eventTitle == null || eventTitle.isBlank()) {
            return "InnBucks ticket booking " + shortId(bookingId);
        }
        String title = eventTitle.length() > 60 ? eventTitle.substring(0, 60) : eventTitle;
        return "Ticketize " + title + " booking " + shortId(bookingId);
    }

    private static UUID parseBookingId(String orderRef) {
        try {
            return UUID.fromString(orderRef);
        } catch (RuntimeException e) {
            throw new InvalidPaymentRequestException(
                    "orderRef must be a booking UUID for BOOKING payments", 400);
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value == null) return null;
        try { return UUID.fromString(value.toString()); } catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
