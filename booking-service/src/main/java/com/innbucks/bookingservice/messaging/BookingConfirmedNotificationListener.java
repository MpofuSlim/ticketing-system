package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.List;

/**
 * Notifies the customer that their booking is confirmed and their tickets are
 * ready. Fires on every {@link BookingDomainEvent.BookingConfirmed} event
 * AFTER the booking transaction commits — same hook the audit identified as
 * the right place for customer notifications (mirrors the
 * {@code PaymentNotificationListener} pattern in payment-service).
 *
 * <p>Channel order: <b>WhatsApp primary → SMS fallback</b>. WhatsApp is
 * preferred because it carries longer copy (1600 chars vs SMS's 160) and
 * supports read receipts; SMS catches every customer whose WhatsApp is off or
 * unreachable. The QR image isn't embedded — the {@code custom-notification}
 * endpoint is text-only — but the ticket number IS the QR payload, so quoting
 * it lets a customer present it manually at the gate if their app is offline.
 *
 * <p><b>Best-effort.</b> A gateway failure on either channel logs a warning
 * and is swallowed — the booking is already CONFIRMED and the customer can
 * still see their tickets in the app. A separate {@link BookingEventPublisher}
 * listener also fires on this event (BEFORE_COMMIT, writing to the outbox for
 * Kafka) — the two are independent.
 *
 * <p>Idempotent replays of {@code confirmBooking} return early without
 * re-publishing the event, so this listener fires exactly once per actual
 * confirmation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConfirmedNotificationListener {

    private final BookingRepository bookingRepository;
    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingConfirmed(BookingDomainEvent.BookingConfirmed event) {
        // The event is intentionally lean (it's also the outbox/Kafka payload),
        // so reload the booking to pick up phone + items. AFTER_COMMIT, no
        // race — the row is committed and visible.
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("BookingConfirmed listener: booking not found bookingId={} — skipping notification",
                    event.bookingId());
            return;
        }
        String phone = booking.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("BookingConfirmed listener: no phone on booking {} — skipping notification (no email path yet)",
                    event.confirmationNumber());
            return;
        }

        String whatsAppMessage = buildWhatsAppMessage(booking);
        try {
            whatsApp.sendCustomNotification(phone, whatsAppMessage);
            log.info("Booking-confirm WhatsApp sent bookingId={} ref={}",
                    booking.getId(), booking.getConfirmationNumber());
            return;
        } catch (RuntimeException waEx) {
            log.warn("Booking-confirm WhatsApp failed bookingId={}, trying SMS: {}",
                    booking.getId(), waEx.getMessage());
        }

        try {
            sms.sendSms(phone, buildSmsMessage(booking),
                    "BOOKING-CONFIRM-" + booking.getId());
            log.info("Booking-confirm SMS sent bookingId={} ref={}",
                    booking.getId(), booking.getConfirmationNumber());
        } catch (RuntimeException smsEx) {
            log.warn("Booking-confirm notification failed bookingId={} (booking still CONFIRMED): {}",
                    booking.getId(), smsEx.getMessage());
        }
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
        sb.append(". Show the QR code from the InnBucks app at the gate.");
        if (!items.isEmpty()) {
            sb.append(" Ticket number");
            if (items.size() > 1) sb.append("s");
            sb.append(": ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i).getTicketNumber());
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private String buildSmsMessage(Booking booking) {
        // SMS lane is the fallback — keep it short. One line, no ticket-number
        // dump (a 4-seat booking would blow past 160 chars and segment-bill).
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
        sb.append(". Show QR in the InnBucks app at the gate.");
        return sb.toString();
    }

    private static void appendTotal(StringBuilder sb, Booking booking) {
        BigDecimal total = booking.getTotalAmount();
        if (total != null) {
            sb.append(", total ").append(total.toPlainString());
        }
    }
}
