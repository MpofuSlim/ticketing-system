package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Delivers a confirmed booking's tickets to the customer — the single
 * implementation behind BOTH the automatic send on confirmation
 * ({@link com.innbucks.bookingservice.messaging.BookingConfirmedNotificationListener})
 * and the manual organizer/admin resend
 * ({@link com.innbucks.bookingservice.controller.TicketResendController}).
 *
 * <p>Two INDEPENDENT, best-effort channels to the PURCHASER — a failure on
 * either never affects the committed booking:
 * <ul>
 *   <li><b>Email</b> (to the booking's {@code userEmail}, if present) — a
 *       plain-text confirmation (booking ref, tickets, total) sent via the
 *       InnBucks notification API. Plain text matches the SMS/WhatsApp standard;
 *       the scannable QR is delivered over WhatsApp, so the email points the
 *       customer there for gate entry.</li>
 *   <li><b>WhatsApp</b> (to {@code phoneNumber}, if present) — one approved
 *       Twilio Content Template send per ticket via the gateway's
 *       {@code /api/messages/event-qr-code} endpoint, delivering the scannable
 *       QR image. The free-text booking summary (booking ref, ticket numbers,
 *       total) is concatenated into the template's {@code eventName} variable
 *       so it renders inline with the QR — there is NO separate
 *       {@code /api/messages/send} text message. One endpoint, one channel.</li>
 * </ul>
 *
 * <p><b>Each ticket's WhatsApp QR goes to its HOLDER's phone, exclusively.</b>
 * A ticket whose attendee has their OWN phone (distinct from the purchaser's)
 * is delivered to that attendee only — the purchaser does NOT receive that
 * ticket's QR, just their own tickets'. A ticket with no attendee phone (none
 * named, name-only, email-only, or the attendee IS the purchaser) stays on the
 * purchaser's WhatsApp, so the gate credential always reaches someone. The
 * purchaser's confirmation EMAIL remains the full receipt for the whole
 * booking (they paid), and it says which guests were sent their ticket
 * directly. An attendee with an email additionally gets a one-ticket
 * confirmation email. Each attendee send is its own best-effort unit: one
 * guest's bad number never costs another guest their ticket — and the
 * purchaser can always recover ANY ticket's QR from the ticket wallet /
 * hosted booking page, which still shows the whole booking.
 *
 * <p>Trade-off: WhatsApp is the only phone channel. There's no SMS fallback —
 * if WhatsApp delivery fails, the email is the only customer-visible artifact.
 * The QR-template send works on the Twilio business-initiated message window
 * (no 24-hour-window restriction), so this is the most reliable phone surface
 * we have; the previous free-text fallback only delivered inside an open
 * customer-initiated window anyway.
 */
@Component
@Slf4j
public class TicketDeliveryService {

    /** Event-title fallback when event-service can't be reached (cosmetic only). */
    private static final String EVENT_NAME_FALLBACK = "your event";

    private final WhatsAppNotificationClient whatsApp;
    private final EmailNotificationClient email;
    private final EventServiceClient eventServiceClient;

    /**
     * Public edge base for the hosted ticket page — the same value
     * {@code TicketController} uses. Only consulted for the attendee email,
     * which links the guest to their ticket online in case the WhatsApp QR
     * never lands. Blank (unit tests, unprovisioned cell) = no link, the email
     * still carries the ticket number.
     */
    @Value("${innbucks.tickets.public-base-url:}")
    private String publicBaseUrl = "";

    public TicketDeliveryService(WhatsAppNotificationClient whatsApp,
                                 EmailNotificationClient email,
                                 EventServiceClient eventServiceClient) {
        this.whatsApp = whatsApp;
        this.email = email;
        this.eventServiceClient = eventServiceClient;
    }

    /**
     * Per-channel result of one delivery attempt, so a manual resend can show
     * the operator exactly what went out. {@code emailAttempted}/{@code
     * whatsappAttempted} are false when the booking simply has no address /
     * phone for that channel (not a failure).
     *
     * <p>{@code attendeeDeliveriesTotal} counts tickets carrying an attendee
     * contact distinct from the purchaser's; {@code attendeeDeliveriesSent}
     * how many of those had at least one channel succeed.
     */
    public record Outcome(boolean emailAttempted, boolean emailSent,
                          boolean whatsappAttempted, int qrTicketsSent, int qrTicketsTotal,
                          int attendeeDeliveriesSent, int attendeeDeliveriesTotal) {

        /** Pre-V22 shape — keeps existing callers/tests compiling. */
        public Outcome(boolean emailAttempted, boolean emailSent,
                       boolean whatsappAttempted, int qrTicketsSent, int qrTicketsTotal) {
            this(emailAttempted, emailSent, whatsappAttempted, qrTicketsSent, qrTicketsTotal, 0, 0);
        }

        public boolean anyChannelAttempted() {
            return emailAttempted || whatsappAttempted || attendeeDeliveriesTotal > 0;
        }
    }

    /**
     * Send the booking's tickets over every channel the booking has an address
     * for. Best-effort per channel and per ticket; never throws for a delivery
     * failure. The booking's {@code items} must be initialized (callers load
     * the booking inside a transaction or via a fetch-join).
     */
    public Outcome deliver(Booking booking) {
        // One event-service round trip for the whole delivery, not one per
        // channel/attendee.
        String eventTitle = resolveEventTitle(booking);
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();

        boolean emailAttempted = false;
        boolean emailSent = false;

        // ---- Email (independent best-effort) ----
        String emailAddr = booking.getUserEmail();
        if (emailAddr != null && !emailAddr.isBlank()) {
            emailAttempted = true;
            try {
                // Ref: <=46 chars (API limit) + unique per send (fresh suffix)
                // so a manual RESEND can't be swallowed by provider-side
                // reference dedup. Subject is plain ASCII — the API rejects
                // typographic punctuation in subjects with 400 "Invalid subject".
                email.sendEmail(emailAddr,
                        "Your InnBucks tickets - booking " + booking.getConfirmationNumber(),
                        buildConfirmationText(booking, eventTitle),
                        "CONF-" + booking.getConfirmationNumber() + "-"
                                + java.util.UUID.randomUUID().toString().substring(0, 6));
                emailSent = true;
                log.info("Booking-confirm email sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm email failed bookingId={} (booking still CONFIRMED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        // ---- WhatsApp QR e-tickets (only — no /send call) ----
        // Route each QR to its holder: tickets whose attendee has their OWN
        // phone go to that attendee (below); everything else is the
        // purchaser's to receive. The purchaser deliberately does NOT get a
        // copy of an attendee-routed QR — "A gets his ticket, B gets his".
        List<BookingItem> purchaserItems = items.stream()
                .filter(i -> !attendeeHasOwnPhone(booking, i))
                .toList();
        boolean whatsappAttempted = false;
        int sent = 0;
        int total = 0;
        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank() && !purchaserItems.isEmpty()) {
            whatsappAttempted = true;
            total = purchaserItems.size();
            sent = sendQrETickets(booking, phone, purchaserItems, eventTitle);
        }

        // ---- Named attendees: their own ticket, to their own contact ----
        int attendeeTotal = 0;
        int attendeeSent = 0;
        for (BookingItem item : items) {
            if (!deliverableToAttendee(booking, item)) {
                continue;
            }
            attendeeTotal++;
            if (deliverToAttendee(booking, item, eventTitle)) {
                attendeeSent++;
            }
        }

        Outcome outcome = new Outcome(emailAttempted, emailSent, whatsappAttempted, sent, total,
                attendeeSent, attendeeTotal);
        if (!outcome.anyChannelAttempted()) {
            log.warn("Ticket delivery: no email or phone on booking {} — no delivery channel",
                    booking.getConfirmationNumber());
        }
        return outcome;
    }

    /**
     * One Twilio Content Template send per ticket. The template body is
     * <em>"Event confirmed! Here is your e-ticket entry for {eventName}. Only
     * present this ticket at the gate."</em>, so we pack the actual event title
     * AND the booking summary into the {@code eventName} variable — the
     * customer sees the QR image plus the full confirmation text in one render.
     * Each call is independent best-effort. Returns how many sends succeeded.
     */
    private int sendQrETickets(Booking booking, String phone, List<BookingItem> items, String eventTitle) {
        if (items.isEmpty()) {
            return 0;
        }
        String eventName = buildEventNameField(booking, eventTitle);
        int sent = 0;
        for (BookingItem item : items) {
            if (sendQr(booking, item, phone, eventName)) {
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Booking-confirm QR e-tickets sent bookingId={} ref={} count={}/{}",
                    booking.getId(), booking.getConfirmationNumber(), sent, items.size());
        }
        return sent;
    }

    /** One QR template send for one ticket to one phone. Best-effort; true on success. */
    private boolean sendQr(Booking booking, BookingItem item, String phone, String eventName) {
        String tn = item.getTicketNumber();
        if (tn == null || tn.isBlank()) {
            return false;
        }
        // `.png` suffix: the WhatsApp gateway / Twilio media fetch is
        // happier with a recognised image extension on the URL. The endpoint
        // serves the identical PNG at both /qr and /qr.png (TicketController),
        // so this only changes the URL string, not the bytes or Content-Type.
        //
        // NOT edge-prefixed, deliberately. The WhatsApp gateway's configured
        // BASE_URL already ends in `/foundry/brand`, so it builds
        // BASE_URL + this path = /foundry/brand/bookings/... — which
        // TicketController serves as an explicit alias for exactly this
        // reason. Adding the prefix here produces /foundry/brand/foundry/...
        // and Twilio fails the media fetch with 63019.
        String qrCodePath = "/bookings/" + booking.getId() + "/tickets/" + tn + "/qr.png";
        try {
            whatsApp.sendEventQrCode(phone, eventName, qrCodePath);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Booking-confirm QR e-ticket failed bookingId={} ticket={} "
                            + "(other channels/tickets unaffected): {}",
                    booking.getId(), tn, ex.getMessage());
            return false;
        }
    }

    /**
     * True when this ticket's QR is routed to the ATTENDEE's WhatsApp rather
     * than the purchaser's: the attendee has a phone of their own, distinct
     * from the purchaser's. Name-only and email-only attendees keep their QR
     * on the purchaser's phone — the QR is the gate credential and must always
     * reach a phone that exists.
     */
    private static boolean attendeeHasOwnPhone(Booking booking, BookingItem item) {
        String attendeePhone = item.getAttendeePhone();
        return attendeePhone != null && !attendeePhone.isBlank()
                && !attendeePhone.equals(booking.getPhoneNumber());
    }

    /**
     * A ticket is delivered to its attendee only when there is an attendee
     * contact AND it is not simply the purchaser's own — an attendee whose
     * number/address IS the purchaser's already receives everything on the
     * purchaser channels, so a second send would be a duplicate, not a
     * delivery.
     */
    private static boolean deliverableToAttendee(Booking booking, BookingItem item) {
        if (!item.hasAttendeeContact()) {
            return false;
        }
        boolean phoneIsPurchasers = item.getAttendeePhone() != null
                && item.getAttendeePhone().equals(booking.getPhoneNumber());
        boolean emailIsPurchasers = item.getAttendeeEmail() != null
                && booking.getUserEmail() != null
                && item.getAttendeeEmail().equalsIgnoreCase(booking.getUserEmail());
        boolean hasOwnPhone = item.getAttendeePhone() != null && !item.getAttendeePhone().isBlank()
                && !phoneIsPurchasers;
        boolean hasOwnEmail = item.getAttendeeEmail() != null && !item.getAttendeeEmail().isBlank()
                && !emailIsPurchasers;
        return hasOwnPhone || hasOwnEmail;
    }

    /**
     * Deliver ONE ticket to the attendee named on it: QR over WhatsApp (if
     * they have a phone) and a one-ticket confirmation email (if they have an
     * address). Best-effort per channel; true when at least one landed.
     */
    private boolean deliverToAttendee(Booking booking, BookingItem item, String eventTitle) {
        boolean any = false;
        String attendeePhone = item.getAttendeePhone();
        if (attendeePhone != null && !attendeePhone.isBlank()
                && !attendeePhone.equals(booking.getPhoneNumber())) {
            if (sendQr(booking, item, attendeePhone, buildAttendeeEventNameField(booking, item, eventTitle))) {
                any = true;
                log.info("Attendee QR e-ticket sent bookingId={} ticket={}", booking.getId(), item.getTicketNumber());
            }
        }
        String attendeeEmail = item.getAttendeeEmail();
        if (attendeeEmail != null && !attendeeEmail.isBlank()
                && (booking.getUserEmail() == null || !attendeeEmail.equalsIgnoreCase(booking.getUserEmail()))) {
            try {
                email.sendEmail(attendeeEmail,
                        "Your InnBucks ticket - booking " + booking.getConfirmationNumber(),
                        buildAttendeeConfirmationText(booking, item, eventTitle),
                        "ATT-" + booking.getConfirmationNumber() + "-"
                                + java.util.UUID.randomUUID().toString().substring(0, 6));
                any = true;
                log.info("Attendee email sent bookingId={} ticket={}", booking.getId(), item.getTicketNumber());
            } catch (RuntimeException ex) {
                log.warn("Attendee email failed bookingId={} ticket={} (other tickets unaffected): {}",
                        booking.getId(), item.getTicketNumber(), ex.getMessage());
            }
        }
        return any;
    }

    /**
     * Build the value injected into the Twilio template's single
     * {@code eventName} variable. The template renders it MID-SENTENCE —
     * <em>"Event confirmed! Here is your e-ticket entry for {eventName}. Only
     * present this ticket at the gate."</em> — so this must read as a noun
     * phrase, e.g.
     * <pre>InnBucks Annual Gala 2025 (booking INN-..., 2 tickets, total 20.00 — TN1, TN2)</pre>
     *
     * <p><b>Must be a single line.</b> WhatsApp template variables cannot
     * contain newlines, tabs, or &gt;4 consecutive spaces — any of those makes
     * Twilio reject the whole message (image included) with error 63021
     * ("channel invalid content"). The earlier multi-line version (title \n\n
     * summary \n\n sign-off) tripped exactly that. No sign-off here either: it
     * read awkwardly mid-sentence and the template is already branded
     * transactional copy.
     */
    private String buildEventNameField(Booking booking, String eventTitle) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder(eventTitle)
                .append(" (booking ").append(booking.getConfirmationNumber());
        if (items.size() == 1) {
            sb.append(", 1 ticket");
        } else if (items.size() > 1) {
            sb.append(", ").append(items.size()).append(" tickets");
        }
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
        // Ticket numbers as the gate reference, after an en-dash. Single line,
        // comma-separated — no newlines (WhatsApp template-variable rule).
        if (!items.isEmpty()) {
            sb.append(" — ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i).getTicketNumber());
                String who = items.get(i).getAttendeeName();
                if (who != null && !who.isBlank()) {
                    sb.append(" (").append(singleLine(who)).append(')');
                }
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * The attendee's variant of {@link #buildEventNameField}: ONE ticket, in
     * their name, with who booked it — same single-line rule.
     * <pre>Harare Jazz Festival (ticket for Tendai Ncube, booked by Alice Moyo, booking INN-..., 20260502-12345A)</pre>
     */
    private String buildAttendeeEventNameField(Booking booking, BookingItem item, String eventTitle) {
        StringBuilder sb = new StringBuilder(eventTitle)
                .append(" (ticket for ").append(singleLine(item.getAttendeeName()));
        if (booking.getCustomerName() != null && !booking.getCustomerName().isBlank()) {
            sb.append(", booked by ").append(singleLine(booking.getCustomerName()));
        }
        sb.append(", booking ").append(booking.getConfirmationNumber())
          .append(", ").append(item.getTicketNumber())
          .append(')');
        return sb.toString();
    }

    /**
     * Plain-text confirmation email body — the same information as the WhatsApp
     * summary (event, booking ref, ticket count, total, ticket numbers), one
     * fact per line. The notification API is plain-text only; the scannable QR
     * e-ticket(s) are delivered over WhatsApp, so this email is the textual
     * record and points the customer at that QR for gate entry.
     */
    private String buildConfirmationText(Booking booking, String eventTitle) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("Hi");
        if (booking.getCustomerName() != null && !booking.getCustomerName().isBlank()) {
            sb.append(' ').append(booking.getCustomerName().trim());
        }
        sb.append("! Your booking is confirmed.\n\n");
        sb.append("Event: ").append(eventTitle).append('\n');
        sb.append("Booking reference: ").append(booking.getConfirmationNumber()).append('\n');
        if (!items.isEmpty()) {
            sb.append("Tickets: ").append(items.size()).append('\n');
        }
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append("Total: ").append(total.toPlainString()).append('\n');
        }
        if (!items.isEmpty()) {
            sb.append("Ticket numbers: ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(items.get(i).getTicketNumber());
                String who = items.get(i).getAttendeeName();
                if (who != null && !who.isBlank()) {
                    sb.append(" (").append(who.trim()).append(')');
                }
            }
            sb.append('\n');
        }
        // Tell the buyer exactly where each QR went: attendee-routed tickets
        // are NOT on the buyer's phone (by design), so without this line a
        // buyer who can't find Tendai's QR assumes delivery failed.
        long routedToAttendees = items.stream().filter(i -> attendeeHasOwnPhone(booking, i)).count();
        long ownTickets = items.size() - routedToAttendees;
        if (routedToAttendees > 0) {
            sb.append("Tickets for attendees with their own phone number have been sent directly to their WhatsApp.\n");
        }
        if (ownTickets > 0) {
            sb.append("\nYour ").append(ownTickets == 1 ? "scannable e-ticket has" : "scannable e-tickets have")
                    .append(" been sent to your WhatsApp — present the QR at the gate.");
        } else {
            sb.append("\nEvery ticket has been sent to its attendee's WhatsApp. You can view the whole booking online at any time.");
        }
        return sb.toString();
    }

    /**
     * Plain-text confirmation for ONE attendee's ticket. Names who booked it
     * (so the email is not a mystery), the ticket number as the gate
     * reference, and — when the cell has a public base URL — the hosted
     * ticket page in case the WhatsApp QR doesn't arrive.
     */
    private String buildAttendeeConfirmationText(Booking booking, BookingItem item, String eventTitle) {
        StringBuilder sb = new StringBuilder("Hi ").append(item.getAttendeeName().trim()).append("!\n\n");
        if (booking.getCustomerName() != null && !booking.getCustomerName().isBlank()) {
            sb.append(booking.getCustomerName().trim()).append(" has booked a ticket for you.\n\n");
        } else {
            sb.append("A ticket has been booked for you.\n\n");
        }
        sb.append("Event: ").append(eventTitle).append('\n');
        sb.append("Booking reference: ").append(booking.getConfirmationNumber()).append('\n');
        sb.append("Your ticket number: ").append(item.getTicketNumber()).append('\n');
        if (item.getCategoryName() != null && !item.getCategoryName().isBlank()) {
            sb.append("Ticket type: ").append(item.getCategoryName()).append('\n');
        }
        boolean hasPhone = item.getAttendeePhone() != null && !item.getAttendeePhone().isBlank();
        if (hasPhone) {
            sb.append("\nYour scannable e-ticket has been sent to your WhatsApp — present the QR at the gate.");
        } else {
            sb.append("\nPresent your ticket number at the gate.");
        }
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            sb.append("\nView the tickets online: ").append(publicBaseUrl)
              .append("/bookings/").append(booking.getId()).append("/tickets");
        }
        return sb.toString();
    }

    /** Collapse anything that would break a WhatsApp template variable. */
    private static String singleLine(String v) {
        if (v == null) return "";
        return v.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
    }

    /**
     * Resolves the event's actual title via event-service. Best-effort —
     * a circuit-broken event-service degrades to a generic fallback rather
     * than dropping the QR delivery.
     */
    private String resolveEventTitle(Booking booking) {
        if (booking.getEventId() == null) {
            return EVENT_NAME_FALLBACK;
        }
        try {
            ApiResult<EventLookupDTO> resp = eventServiceClient.getEvent(booking.getEventId());
            if (resp != null && resp.getData() != null) {
                String title = resp.getData().getTitle();
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Event name lookup failed bookingId={} eventId={} — using fallback: {}",
                    booking.getId(), booking.getEventId(), ex.getMessage());
        }
        return EVENT_NAME_FALLBACK;
    }
}
