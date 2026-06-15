package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
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
 *   <li><b>WhatsApp → SMS fallback</b> (to {@code phoneNumber}, if present) —
 *       a plain confirmation (booking ref, ticket count/numbers, total). NO
 *       links by product direction; the scannable QR reaches the customer via
 *       the email (and the hosted endpoints stay available for later).</li>
 * </ul>
 *
 * <p>Both links/images are absolute, built from
 * {@code innbucks.tickets.public-base-url} (the public gateway origin) so they
 * resolve from an email client or phone with no app session.
 */
@Component
@Slf4j
public class BookingConfirmedNotificationListener {

    private final BookingRepository bookingRepository;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final EmailNotificationClient email;
    private final TicketRenderingService ticketRendering;
    private final EventServiceClient eventServiceClient;
    private final String publicBaseUrl;

    /** eventName fallback when the event title can't be resolved (event-service down). */
    private static final String EVENT_NAME_FALLBACK = "your event";

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            WhatsAppNotificationClient whatsApp,
            SmsNotificationClient sms,
            EmailNotificationClient email,
            TicketRenderingService ticketRendering,
            EventServiceClient eventServiceClient,
            @Value("${innbucks.tickets.public-base-url:}") String publicBaseUrl) {
        this.bookingRepository = bookingRepository;
        this.whatsApp = whatsApp;
        this.sms = sms;
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

        // ---- WhatsApp → SMS fallback (independent best-effort) ----
        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            anyChannel = true;
            try {
                whatsApp.sendCustomNotification(phone, buildWhatsAppMessage(booking));
                log.info("Booking-confirm WhatsApp sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException waEx) {
                log.warn("Booking-confirm WhatsApp failed bookingId={}, trying SMS: {}",
                        booking.getId(), waEx.getMessage());
                try {
                    sms.sendSms(phone, buildSmsMessage(booking),
                            "BOOKING-CONFIRM-" + booking.getId());
                    log.info("Booking-confirm SMS sent bookingId={} ref={}",
                            booking.getId(), booking.getConfirmationNumber());
                } catch (RuntimeException smsEx) {
                    log.warn("Booking-confirm SMS also failed bookingId={} (booking still CONFIRMED): {}",
                            booking.getId(), smsEx.getMessage());
                }
            }

            // ---- Scannable QR e-tickets on WhatsApp (independent best-effort) ----
            // In ADDITION to the text confirmation above, deliver each ticket as a
            // scannable QR image via the gateway's event-qr-code template. The QR
            // is fetched by the gateway from our public, CONFIRMED-only ticket
            // endpoint (BASE_URL + qrCodePath). One send per ticket — each is a
            // separate gate-entry image; a failure on one never blocks the others
            // (or the already-delivered text/email).
            sendQrETickets(booking, phone);
        }

        if (!anyChannel) {
            log.warn("BookingConfirmed listener: no email or phone on booking {} — no ticket delivery channel",
                    booking.getConfirmationNumber());
        }
    }

    /**
     * Sends one scannable QR e-ticket per booking item to the customer's
     * WhatsApp. Each call is independent best-effort — a rejection on one image
     * is logged and skipped so the rest still go out. The qrCodePath is the
     * public hosted ticket-QR endpoint (domain-relative; the gateway prepends
     * its BASE_URL), which only serves CONFIRMED bookings.
     */
    private void sendQrETickets(Booking booking, String phone) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        if (items.isEmpty()) {
            return;
        }
        String eventName = resolveEventName(booking);
        int sent = 0;
        for (BookingItem item : items) {
            String tn = item.getTicketNumber();
            if (tn == null || tn.isBlank()) {
                continue;
            }
            String qrCodePath = "/bookings/" + booking.getId() + "/tickets/" + tn + "/qr";
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
     * Resolves the event's display name for the e-ticket message. Best-effort —
     * event-service is circuit-broken, and the event name is cosmetic copy, not
     * gate-critical data, so a lookup failure degrades to a generic fallback
     * rather than dropping the QR delivery.
     */
    private String resolveEventName(Booking booking) {
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

    private String buildWhatsAppMessage(Booking booking) {
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("InnBucks: your booking ")
                .append(booking.getConfirmationNumber())
                .append(" is confirmed");
        if (items.size() == 1) {
            sb.append(" — 1 ticket");
        } else if (items.size() > 1) {
            sb.append(" — ").append(items.size()).append(" tickets");
        }
        appendTotal(sb, booking);
        sb.append('.');
        // No links by product direction — just the ticket number(s) as the
        // manual reference at the gate. The QR reaches the customer via email.
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

    private String buildSmsMessage(Booking booking) {
        // SMS fallback — one short segment, no links (product direction).
        StringBuilder sb = new StringBuilder("InnBucks: booking ")
                .append(booking.getConfirmationNumber())
                .append(" confirmed");
        int n = booking.getItems() == null ? 0 : booking.getItems().size();
        if (n == 1) {
            sb.append(" (1 ticket)");
        } else if (n > 1) {
            sb.append(" (").append(n).append(" tickets)");
        }
        appendTotal(sb, booking);
        sb.append('.');
        return sb.toString();
    }

    private static void appendTotal(StringBuilder sb, Booking booking) {
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
    }
}
