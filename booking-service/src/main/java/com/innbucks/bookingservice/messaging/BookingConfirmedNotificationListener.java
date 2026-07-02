package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * Delivers the customer's tickets once a booking is confirmed. Fires on
 * {@link BookingDomainEvent.BookingConfirmed} AFTER the booking transaction
 * commits (idempotent confirm = exactly one event per real confirmation).
 *
 * <p>Two INDEPENDENT, best-effort channels — a failure on either never affects
 * the committed booking:
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
 * <p>Trade-off: WhatsApp is the only phone channel. There's no SMS fallback —
 * if WhatsApp delivery fails, the email is the only customer-visible artifact.
 * The QR-template send works on the Twilio business-initiated message window
 * (no 24-hour-window restriction), so this is the most reliable phone surface
 * we have; the previous free-text fallback only delivered inside an open
 * customer-initiated window anyway.
 */
@Component
@Slf4j
public class BookingConfirmedNotificationListener {

    private final BookingRepository bookingRepository;
    private final WhatsAppNotificationClient whatsApp;
    private final EmailNotificationClient email;
    private final EventServiceClient eventServiceClient;

    /** Event-title fallback when event-service can't be reached (cosmetic only). */
    private static final String EVENT_NAME_FALLBACK = "your event";

    /** Public base URL of the customer-facing e-ticket page (env
     *  TICKETS_PUBLIC_BASE_URL). Lets the confirmation email carry a durable link
     *  to the scannable QR e-ticket(s), so the ticket is reachable even when the
     *  WhatsApp gateway is down. Blank -> the email omits the link. */
    @org.springframework.beans.factory.annotation.Value("${innbucks.tickets.public-base-url:}")
    private String publicBaseUrl;

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            WhatsAppNotificationClient whatsApp,
            EmailNotificationClient email,
            EventServiceClient eventServiceClient) {
        this.bookingRepository = bookingRepository;
        this.whatsApp = whatsApp;
        this.email = email;
        this.eventServiceClient = eventServiceClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingConfirmed(BookingDomainEvent.BookingConfirmed event) {
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("BookingConfirmed listener: booking not found bookingId={} — skipping notifications",
                    event.bookingId());
            return;
        }
        boolean anyChannel = false;

        // ---- Email (independent best-effort) ----
        String emailAddr = booking.getUserEmail();
        if (emailAddr != null && !emailAddr.isBlank()) {
            anyChannel = true;
            try {
                email.sendEmail(emailAddr,
                        "Your InnBucks tickets — booking " + booking.getConfirmationNumber(),
                        buildConfirmationText(booking),
                        "BOOKING-CONFIRM-" + booking.getId());
                log.info("Booking-confirm email sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm email failed bookingId={} (booking still CONFIRMED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        // ---- WhatsApp QR e-tickets (only — no /send call) ----
        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            anyChannel = true;
            sendQrETickets(booking, phone);
        }

        if (!anyChannel) {
            log.warn("BookingConfirmed listener: no email or phone on booking {} — no ticket delivery channel",
                    booking.getConfirmationNumber());
        }
    }

    /**
     * One Twilio Content Template send per ticket. The template body is
     * <em>"Event confirmed! Here is your e-ticket entry for {eventName}. Only
     * present this ticket at the gate."</em>, so we pack the actual event title
     * AND the booking summary into the {@code eventName} variable — the
     * customer sees the QR image plus the full confirmation text in one render.
     * Each call is independent best-effort.
     */
    private void sendQrETickets(Booking booking, String phone) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        if (items.isEmpty()) {
            return;
        }
        String eventName = buildEventNameField(booking);
        int sent = 0;
        for (BookingItem item : items) {
            String tn = item.getTicketNumber();
            if (tn == null || tn.isBlank()) {
                continue;
            }
            // `.png` suffix: the WhatsApp gateway / Twilio media fetch is
            // happier with a recognised image extension on the URL. The endpoint
            // serves the identical PNG at both /qr and /qr.png (TicketController),
            // so this only changes the URL string, not the bytes or Content-Type.
            String qrCodePath = "/bookings/" + booking.getId() + "/tickets/" + tn + "/qr.png";
            try {
                whatsApp.sendEventQrCode(phone, eventName, qrCodePath);
                sent++;
            } catch (RuntimeException ex) {
                log.warn("Booking-confirm QR e-ticket failed bookingId={} ticket={} "
                                + "(other channels/tickets unaffected): {}",
                        booking.getId(), tn, ex.getMessage());
            }
        }
        if (sent > 0) {
            log.info("Booking-confirm QR e-tickets sent bookingId={} ref={} count={}/{}",
                    booking.getId(), booking.getConfirmationNumber(), sent, items.size());
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
    private String buildEventNameField(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder(resolveEventTitle(booking))
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
            }
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Plain-text confirmation email body — the same information as the WhatsApp
     * summary (event, booking ref, ticket count, total, ticket numbers), one
     * fact per line. The notification API is plain-text only; the scannable QR
     * e-ticket(s) are delivered over WhatsApp, so this email is the textual
     * record and points the customer at that QR for gate entry.
     */
    private String buildConfirmationText(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("Hi! Your booking is confirmed.\n\n");
        sb.append("Event: ").append(resolveEventTitle(booking)).append('\n');
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
            }
            sb.append('\n');
        }
        boolean many = items.size() != 1;
        sb.append('\n');
        // The scannable QR is delivered over WhatsApp, but the WhatsApp gateway
        // can be down — so ALWAYS put a durable e-ticket link in the email too,
        // making email a self-sufficient way to reach the QR at the gate.
        String link = eTicketUrl(booking);
        if (link != null) {
            sb.append("View your scannable e-ticket").append(many ? "s" : "")
                    .append(": ").append(link).append('\n');
        }
        if (booking.getPhoneNumber() != null && !booking.getPhoneNumber().isBlank()) {
            sb.append("Your QR e-ticket").append(many ? "s have" : " has")
                    .append(" also been sent to your WhatsApp.\n");
        }
        sb.append("Present the QR at the gate.\n\n— The InnBucks Team");
        return sb.toString();
    }

    /**
     * Absolute URL of the customer-facing e-ticket page
     * ({@code /bookings/{id}/tickets} — public, CONFIRMED-only, renders every
     * ticket's scannable QR) for the confirmation email. Returns null when no
     * public base URL is configured, so the email simply omits the link rather
     * than emitting a broken relative one.
     */
    private String eTicketUrl(Booking booking) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return null;
        }
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/bookings/" + booking.getId() + "/tickets";
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
