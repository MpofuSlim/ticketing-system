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
import com.innbucks.bookingservice.service.TicketRenderingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>Email</b> (to the booking's {@code userEmail}, if present) — an HTML
 *       message with each seat's scannable QR, rendered from the hosted ticket
 *       endpoint ({@code /bookings/{id}/tickets/{tn}/qr}) so it shows in Gmail/
 *       Outlook (data-URIs are stripped), plus a link to the ticket page.</li>
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
    private final TicketRenderingService ticketRendering;
    private final EventServiceClient eventServiceClient;
    private final String publicBaseUrl;

    /** Event-title fallback when event-service can't be reached (cosmetic only). */
    private static final String EVENT_NAME_FALLBACK = "your event";

    /** Sign-off appended to the WhatsApp confirmation text. */
    private static final String SIGN_OFF = "• The InnBucks Team";

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            WhatsAppNotificationClient whatsApp,
            EmailNotificationClient email,
            TicketRenderingService ticketRendering,
            EventServiceClient eventServiceClient,
            @Value("${innbucks.tickets.public-base-url:}") String publicBaseUrl) {
        this.bookingRepository = bookingRepository;
        this.whatsApp = whatsApp;
        this.email = email;
        this.ticketRendering = ticketRendering;
        this.eventServiceClient = eventServiceClient;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
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
                email.sendHtmlEmail(emailAddr,
                        "Your InnBucks tickets — booking " + booking.getConfirmationNumber(),
                        ticketRendering.confirmationEmailHtml(booking, publicBaseUrl),
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
     * Build the value that goes into the Twilio template's {@code eventName}
     * variable:
     * <pre>
     * {actual event title}
     *
     * InnBucks: your booking INN-... is confirmed — N tickets, total ....
     * Ticket numbers: TN1, TN2.
     *
     * • The InnBucks Team
     * </pre>
     * The event title preserves per-event context across multiple bookings;
     * the summary line replaces the dropped {@code /api/messages/send} text;
     * the sign-off is brand copy.
     */
    private String buildEventNameField(Booking booking) {
        return resolveEventTitle(booking)
                + "\n\n"
                + buildBookingSummary(booking)
                + "\n\n"
                + SIGN_OFF;
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

    /**
     * Same shape as the text formerly sent via {@code /api/messages/send}:
     * "InnBucks: your booking {ref} is confirmed[ — N tickets][, total {amt}].
     *  Ticket number(s): TN1, TN2."
     */
    private String buildBookingSummary(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("InnBucks: your booking ")
                .append(booking.getConfirmationNumber())
                .append(" is confirmed");
        if (items.size() == 1) {
            sb.append(" — 1 ticket");
        } else if (items.size() > 1) {
            sb.append(" — ").append(items.size()).append(" tickets");
        }
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
        sb.append('.');
        if (!items.isEmpty()) {
            sb.append(" Ticket number").append(items.size() > 1 ? "s" : "").append(": ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i).getTicketNumber());
            }
            sb.append('.');
        }
        return sb.toString();
    }
}
