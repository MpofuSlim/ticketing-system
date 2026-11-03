package com.innbucks.bookingservice.service;

import com.innbucks.common.ticket.TicketNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How a ticket number reaches someone who is not its buyer, its holder or the
 * gate: an organizer's guest list, a booking read by staff, a scan report.
 *
 * <p>A ticket number is the gate credential: the QR holds nothing else, and the
 * first scan wins. Shown in full on those surfaces, it reaches everyone who can
 * see the screen or the export, and any of them can walk in on it before the
 * real holder. With {@code app.booking.mask-ticket-numbers} on, they get
 * {@link TicketNumbers#forDisplay} (date and last symbols) and no QR image.
 *
 * <p>The switch exists for the rollout, not as a setting: the console still
 * edits an attendee by ticket number and searches the numbers it loaded, so
 * masking before it moves to the item-id edit and the server-side search would
 * break both. Turn it on per cell once it has, and remove it (always masked)
 * once every cell runs with it on.
 */
@Component
public class TicketNumberDisplay {

    /** Masking off: every number in full, as before. For hand-built services in tests. */
    public static final TicketNumberDisplay FULL = new TicketNumberDisplay(false);

    private final boolean mask;

    public TicketNumberDisplay(@Value("${app.booking.mask-ticket-numbers:false}") boolean mask) {
        this.mask = mask;
    }

    /** True when staff surfaces get masked numbers and no QR. */
    public boolean masking() {
        return mask;
    }

    /** The number for a reader who is not its buyer, holder or the gate. */
    public String forStaff(String issued) {
        return mask ? TicketNumbers.forDisplay(issued) : issued;
    }
}
