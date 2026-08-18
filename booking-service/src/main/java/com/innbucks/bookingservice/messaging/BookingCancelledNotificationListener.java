package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the customer when their booking is cancelled. Fires on
 * {@link BookingDomainEvent.BookingCancelled} AFTER the cancelling transaction
 * commits — covers all three cancel paths (customer cancel, hold expiry, and an
 * admin reversal of a CONFIRMED booking).
 *
 * <p>The wording adapts to whether money was involved: a CONFIRMED booking that
 * an admin reversed sets {@code availabilityReleased=true} (set ONLY by
 * {@code reverseConfirmedBooking}), so that's the refund case; everything else
 * is a PENDING hold that was never paid.
 *
 * <p>Two independent best-effort destinations: the email address, and the phone
 * number — where the phone leg is <b>SMS first, WhatsApp only if the SMS send
 * fails</b>, mirroring {@code EventChangeNotificationService.deliver}. SMS
 * reaches a customer who has no WhatsApp, no data, or no smartphone, which is
 * the population most likely to miss a cancellation notice entirely. It is
 * deliberately a fallback chain rather than both channels: a cancellation is one
 * message, and sending it twice is both confusing and billable. A failure on any
 * leg never affects the committed cancellation. No QR here — a cancellation
 * isn't a ticket.
 */
@Component
@Slf4j
public class BookingCancelledNotificationListener {

    private final BookingRepository bookingRepository;
    private final EmailNotificationClient email;
    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;

    public BookingCancelledNotificationListener(BookingRepository bookingRepository,
                                                EmailNotificationClient email,
                                                SmsNotificationClient sms,
                                                WhatsAppNotificationClient whatsApp) {
        this.bookingRepository = bookingRepository;
        this.email = email;
        this.sms = sms;
        this.whatsApp = whatsApp;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingCancelled(BookingDomainEvent.BookingCancelled event) {
        Booking booking = bookingRepository.findById(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("BookingCancelled listener: booking not found bookingId={} — skipping notifications",
                    event.bookingId());
            return;
        }

        boolean refund = booking.isAvailabilityReleased();
        // Ref <=46 chars (API limit), unique per send; subject plain ASCII —
        // the API rejects typographic punctuation in subjects ("Invalid subject").
        String ref = "CANCEL-" + booking.getConfirmationNumber() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 6);
        String subject = refund
                ? "Your InnBucks booking " + booking.getConfirmationNumber() + " was cancelled - refund in progress"
                : "Your InnBucks booking " + booking.getConfirmationNumber() + " was cancelled";
        String message = buildCancellationText(booking, refund);

        String emailAddr = booking.getUserEmail();
        if (emailAddr != null && !emailAddr.isBlank()) {
            try {
                email.sendEmail(emailAddr, subject, message, ref);
                // Log the reference we actually SENT, not the confirmation
                // number: the email and SMS legs share one reference precisely
                // so they correlate in the notification API's logs, and printing
                // a different value here defeats that.
                log.info("Booking-cancel email sent bookingId={} ref={} confirmation={} refund={}",
                        booking.getId(), ref, booking.getConfirmationNumber(), refund);
            } catch (RuntimeException ex) {
                log.warn("Booking-cancel email failed bookingId={} (booking still CANCELLED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            deliverToPhone(phone, message, ref, booking);
        }

        if ((emailAddr == null || emailAddr.isBlank()) && (phone == null || phone.isBlank())) {
            log.warn("BookingCancelled listener: no email or phone on booking {} — no cancel notice sent",
                    booking.getConfirmationNumber());
        }
    }

    /**
     * SMS first, WhatsApp only if the SMS send fails. Same shape as
     * {@code EventChangeNotificationService.deliver}, and best-effort throughout:
     * the booking is already CANCELLED and committed, so no delivery outcome may
     * propagate out of here.
     *
     * <p>The SMS reference is the same {@code CANCEL-<conf>-<rand>} value the
     * email carries, so both legs of one cancellation correlate in the
     * notification API's logs.
     */
    private void deliverToPhone(String phone, String message, String ref, Booking booking) {
        try {
            sms.sendSms(phone, message, ref);
            log.info("Booking-cancel SMS sent bookingId={} ref={}", booking.getId(), ref);
            return;
        } catch (RuntimeException smsEx) {
            log.warn("Booking-cancel SMS failed bookingId={}, trying WhatsApp: {}",
                    booking.getId(), smsEx.getMessage());
        }
        try {
            whatsApp.sendCustomNotification(phone, message);
            log.info("Booking-cancel WhatsApp sent bookingId={} ref={}", booking.getId(), ref);
        } catch (RuntimeException waEx) {
            log.warn("Booking-cancel notification failed bookingId={} (both phone channels; "
                    + "booking still CANCELLED): {}", booking.getId(), waEx.getMessage());
        }
    }

    /**
     * Copy shared by every channel. Written to survive {@code SmsTextSanitizer}
     * UNCHANGED — the notification API rejects {@code ! : / ? " * ;} outright and
     * the sanitizer rewrites them, so a question mark here would reach the
     * customer as a full stop. Hence "Contact InnBucks support if you have any
     * questions." rather than "Questions?", and a comma rather than the em dash
     * the refund line used to carry.
     */
    private String buildCancellationText(Booking booking, boolean refund) {
        String ref = booking.getConfirmationNumber();
        if (refund) {
            return "Your booking " + ref + " has been cancelled. If a payment was taken, your refund is being "
                    + "processed, please allow a few business days. Contact InnBucks support if you have "
                    + "any questions.";
        }
        return "Your booking " + ref + " has been cancelled and your reserved seats released. "
                + "You can book again anytime.";
    }
}
