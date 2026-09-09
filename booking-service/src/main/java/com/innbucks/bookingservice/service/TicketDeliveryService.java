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
import java.util.ArrayList;
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
     * the operator exactly what went out. {@code emailAttempted} is false when
     * the booking has no address; {@code whatsappAttempted} is false when the
     * booking has no phone OR no ticket ended up routed to the purchaser
     * (every ticket went to its attendee) — neither is a failure.
     *
     * <p>{@code qrTicketsSent/Total} count PURCHASER-routed QR sends,
     * including fallbacks (an attendee QR that failed and was re-routed to
     * the buyer). {@code attendeeDeliveriesTotal} counts tickets carrying an
     * attendee contact distinct from the purchaser's; {@code
     * attendeeDeliveriesSent} how many of those had at least one channel
     * succeed. The two buckets overlap on an email-only attendee: their QR is
     * purchaser-routed (counted in qrTicketsTotal) while their email counts
     * them in attendeeDeliveriesTotal.
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
        String phone = booking.getPhoneNumber();
        boolean purchaserHasPhone = phone != null && !phone.isBlank();

        // ---- 1. Attendee channels FIRST, so the purchaser's receipt (below)
        // reports what actually happened, and a FAILED attendee QR can fall
        // back to the buyer. Route each QR to its holder: a ticket whose
        // attendee has their OWN phone goes to that attendee — the purchaser
        // deliberately does NOT get a copy ("A gets his ticket, B gets his").
        // The one exception is a KNOWN send failure: the QR is the gate
        // credential and must never be lost, so it is re-routed to the buyer
        // (who forwards it) instead of vanishing with a WARN nobody reads.
        List<BookingItem> purchaserQueue = new ArrayList<>();   // buyer's own + fallbacks, request order
        List<BookingItem> routedOk = new ArrayList<>();         // attendee QR landed
        List<BookingItem> fallbacks = new ArrayList<>();        // attendee QR failed -> buyer
        int attendeeTotal = 0;
        int attendeeSent = 0;
        for (BookingItem item : items) {
            boolean routed = attendeeHasOwnPhone(booking, item);
            boolean qrToAttendee = false;
            if (deliverableToAttendee(booking, item)) {
                attendeeTotal++;
                if (routed) {
                    qrToAttendee = sendQr(booking, item, item.getAttendeePhone(),
                            buildAttendeeEventNameField(booking, item, eventTitle));
                    if (qrToAttendee) {
                        log.info("Attendee QR e-ticket sent bookingId={} ticket={}",
                                booking.getId(), item.getTicketNumber());
                    }
                }
                boolean emailToAttendee = sendAttendeeEmail(booking, item, eventTitle);
                if (qrToAttendee || emailToAttendee) {
                    attendeeSent++;
                }
            }
            if (!routed) {
                purchaserQueue.add(item);
            } else if (qrToAttendee) {
                routedOk.add(item);
            } else if (purchaserHasPhone) {
                fallbacks.add(item);
                purchaserQueue.add(item);
            } else {
                // Attendee unreachable AND the buyer has no phone: the QR
                // reached nobody. The hosted booking page / wallet still hold
                // it; make the loss loud for the ops log.
                log.warn("Attendee QR undeliverable and no purchaser phone to fall back to "
                        + "bookingId={} ticket={}", booking.getId(), item.getTicketNumber());
            }
        }

        // ---- 2. Purchaser WhatsApp QRs: their own tickets + fallbacks ----
        boolean whatsappAttempted = false;
        int sent = 0;
        int total = 0;
        if (purchaserHasPhone && !purchaserQueue.isEmpty()) {
            whatsappAttempted = true;
            total = purchaserQueue.size();
            sent = sendQrETickets(booking, phone, purchaserQueue, eventTitle);
        }

        // ---- 3. Purchaser receipt email, composed from ACTUAL outcomes ----
        boolean emailAttempted = false;
        boolean emailSent = false;
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
                        buildConfirmationText(booking, eventTitle, routedOk, fallbacks, total),
                        "CONF-" + booking.getConfirmationNumber() + "-"
                                + java.util.UUID.randomUUID().toString().substring(0, 6));
                emailSent = true;
                log.info("Booking-confirm email sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm email failed bookingId={} (booking still CONFIRMED): {}",
                        booking.getId(), ex.getMessage());
            }
        } else if (purchaserHasPhone && total == 0 && !items.isEmpty()) {
            // ---- 3b. SMS receipt — ONLY when the buyer would otherwise hear
            // NOTHING: no email on file and no QR routed to their WhatsApp
            // (every ticket went to its attendee). WhatsApp-first customers
            // routinely book with no email; without this the person who PAID
            // gets zero messages. One bounded SMS, only in this exact case.
            try {
                email.sendSms(phone,
                        "Booking " + booking.getConfirmationNumber() + " confirmed for " + eventTitle
                                + ". Your guests' tickets were sent to their WhatsApp numbers.",
                        "CONF-" + booking.getConfirmationNumber().replaceAll("[^A-Za-z0-9-]", "")
                                + "-" + java.util.UUID.randomUUID().toString().substring(0, 4) + "S");
                log.info("Booking-confirm SMS receipt sent bookingId={} (no email, all QRs attendee-routed)",
                        booking.getId());
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm SMS receipt failed bookingId={}: {}",
                        booking.getId(), ex.getMessage());
            }
        }

        Outcome outcome = new Outcome(emailAttempted, emailSent, whatsappAttempted, sent, total,
                attendeeSent, attendeeTotal);
        if (!emailAttempted && !purchaserHasPhone) {
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
     * The email half of an attendee delivery: a one-ticket confirmation to the
     * attendee's own address (skipped when it is the purchaser's). The QR half
     * lives in {@link #deliver}'s routing loop, because a failed attendee QR
     * changes the routing (fallback to the buyer) — the email does not.
     * Best-effort; true when it landed.
     */
    private boolean sendAttendeeEmail(Booking booking, BookingItem item, String eventTitle) {
        String attendeeEmail = item.getAttendeeEmail();
        if (attendeeEmail == null || attendeeEmail.isBlank()
                || (booking.getUserEmail() != null && attendeeEmail.equalsIgnoreCase(booking.getUserEmail()))) {
            return false;
        }
        try {
            email.sendEmail(attendeeEmail,
                    "Your InnBucks ticket - booking " + booking.getConfirmationNumber(),
                    buildAttendeeConfirmationText(booking, item, eventTitle),
                    "ATT-" + booking.getConfirmationNumber() + "-"
                            + java.util.UUID.randomUUID().toString().substring(0, 6));
            log.info("Attendee email sent bookingId={} ticket={}", booking.getId(), item.getTicketNumber());
            return true;
        } catch (RuntimeException ex) {
            log.warn("Attendee email failed bookingId={} ticket={} (other tickets unaffected): {}",
                    booking.getId(), item.getTicketNumber(), ex.getMessage());
            return false;
        }
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
    private String buildConfirmationText(Booking booking, String eventTitle,
                                         List<BookingItem> routedOk, List<BookingItem> fallbacks,
                                         int purchaserQrCount) {
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
        // Tell the buyer exactly where each QR ACTUALLY went — this runs
        // AFTER the sends, so it reports outcomes, not intentions. Without
        // these lines a buyer who can't find a guest's QR on their own phone
        // reads the exclusive routing as a delivery failure.
        if (!routedOk.isEmpty()) {
            sb.append("Tickets for attendees with their own phone number have been sent directly to their WhatsApp.\n");
        }
        for (BookingItem fb : fallbacks) {
            sb.append("We could not reach ")
              .append(fb.getAttendeeName() == null ? "an attendee" : fb.getAttendeeName().trim())
              .append("'s WhatsApp, so their ticket (").append(fb.getTicketNumber())
              .append(") was sent to yours — please forward it.\n");
        }
        if (purchaserQrCount > 0) {
            sb.append("\nYour ").append(purchaserQrCount == 1 ? "scannable e-ticket has" : "scannable e-tickets have")
                    .append(" been sent to your WhatsApp — present the QR at the gate.");
        } else {
            sb.append("\nEvery ticket has been sent to its attendee's WhatsApp.");
        }
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            sb.append("\nView the whole booking online: ").append(publicBaseUrl)
              .append("/bookings/").append(booking.getId()).append("/tickets");
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
