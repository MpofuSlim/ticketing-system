package innbucks.paymentservice.util;

import java.util.UUID;

/**
 * First-8-characters bookingId mask for log lines. A full booking UUID
 * in structured logs is a moderate-risk leak: it's the access token for
 * the public {@code GET /bookings/confirmation/{uuid}} and ticket-render
 * URLs, so anyone with read-access to logs (operators, log-aggregator
 * indexes, error-tracker dashboards) can pull the customer's full
 * confirmation + ticket page out-of-band. Truncating to the first 8
 * characters keeps enough entropy for log-correlation (the prefix is
 * unique across millions of bookings) without making the full identifier
 * grep-able from a log dump.
 *
 * <p>Mirrors {@link MsisdnMasking} so log-masking conventions live in
 * one place per leak class.
 */
public final class BookingIdMasking {

    private BookingIdMasking() {
    }

    public static String mask(UUID bookingId) {
        if (bookingId == null) return "null";
        return bookingId.toString().substring(0, 8) + "...";
    }
}
