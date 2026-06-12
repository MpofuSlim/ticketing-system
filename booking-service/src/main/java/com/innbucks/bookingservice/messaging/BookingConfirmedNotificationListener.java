package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
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
 *       the confirmation text + a DIRECT link to each ticket's QR image
 *       ({@code /bookings/{id}/tickets/{tn}/qr}). The WhatsApp gateway is
 *       text-only, so the QR rides the link, not an attachment; the ticket
 *       view page exists but isn't part of the message for now.</li>
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
    private final String publicBaseUrl;

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            WhatsAppNotificationClient whatsApp,
            SmsNotificationClient sms,
            EmailNotificationClient email,
            TicketRenderingService ticketRendering,
            @Value("${innbucks.tickets.public-base-url:}") String publicBaseUrl) {
        this.bookingRepository = bookingRepository;
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.email = email;
        this.ticketRendering = ticketRendering;
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
        }

        if (!anyChannel) {
            log.warn("BookingConfirmed listener: no email or phone on booking {} — no ticket delivery channel",
                    booking.getConfirmationNumber());
        }
    }

    /** Direct link to one ticket's QR PNG — the only artifact we send for now
     *  (the ticket view page exists but isn't part of the message yet). */
    private String qrLink(Booking booking, BookingItem item) {
        return publicBaseUrl + "/bookings/" + booking.getId()
                + "/tickets/" + item.getTicketNumber() + "/qr";
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
        // One line per ticket: the number (manual fallback at the gate) plus a
        // DIRECT link to its QR image — that's the whole deliverable for now.
        for (BookingItem item : items) {
            sb.append("\nTicket ").append(item.getTicketNumber())
                    .append(" QR: ").append(qrLink(booking, item));
        }
        return sb.toString();
    }

    private String buildSmsMessage(Booking booking) {
        // SMS fallback — direct QR link(s). A single link fits one segment;
        // multi-seat bookings accept the extra segments (best-effort channel).
        List<BookingItem> items = booking.getItems() == null ? List.of() : booking.getItems();
        StringBuilder sb = new StringBuilder("InnBucks: booking ")
                .append(booking.getConfirmationNumber())
                .append(" confirmed");
        if (items.size() == 1) {
            sb.append(" (1 ticket)");
        } else if (items.size() > 1) {
            sb.append(" (").append(items.size()).append(" tickets)");
        }
        sb.append(". QR: ");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(qrLink(booking, items.get(i)));
        }
        return sb.toString();
    }

    private static void appendTotal(StringBuilder sb, Booking booking) {
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
    }
}
