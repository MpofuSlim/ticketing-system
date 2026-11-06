package com.innbucks.bookingservice.util;

import com.innbucks.common.ticket.TicketNumbers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request path as a log line may carry it. Several routes put a credential
 * or personal data in the path itself:
 * <ul>
 *   <li>the hosted QR, fetched by Twilio for every WhatsApp ticket (and the
 *       deleted attendee edit by number, should a client still send it):
 *       {@code /bookings/{id}/tickets/{ticketNumber}/...}. A
 *       ticket number admits whoever holds it, so it is masked as
 *       {@link TicketNumbers#forLog} masks it;</li>
 *   <li>the phone lookups: {@code /bookings/phone/{phone}} and
 *       {@code /bookings/public/phone/{phone}}. The number is masked to its last
 *       four, as {@link MsisdnMasking} masks it everywhere else;</li>
 *   <li>the public ticket pages by link token (V31):
 *       {@code /bookings/view/{token}...} and {@code /bookings/ticket/{token}...},
 *       {@code /brand/} aliases included. A token opens the tickets it keys, so
 *       it is replaced whole by {@code ****}.</li>
 * </ul>
 * Use it for every logged path, never the raw {@code getRequestURI()}.
 * {@code LogLinesCarryNoTicketNumbersTest} fails the build on a log call that
 * passes the raw one. Never throws.
 */
public final class RequestPaths {

    /** A {@code /phone/} segment and the value after it, up to the next slash or query. */
    private static final Pattern PHONE_SEGMENT = Pattern.compile("(/phone/)([^/?#]+)");

    /**
     * A link token's segment: whatever follows {@code /bookings/view/} or
     * {@code /bookings/ticket/}, up to the next slash or query, so a near-miss
     * a caller sent is hidden too.
     */
    private static final Pattern LINK_TOKEN_SEGMENT = Pattern.compile("(/bookings/(?:view|ticket)/)([^/?#]+)");

    private RequestPaths() {
    }

    public static String forLog(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String s = TicketNumbers.redactIn(path);
        s = LINK_TOKEN_SEGMENT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "****"));
        return PHONE_SEGMENT.matcher(s)
                .replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MsisdnMasking.mask(m.group(2))));
    }
}
