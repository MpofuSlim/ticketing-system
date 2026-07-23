package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
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
 * is a PENDING hold that was never paid. Two independent best-effort channels
 * (email + WhatsApp); a failure on either never affects the committed
 * cancellation. No QR here — a cancellation isn't a ticket.
 */
@Component
@Slf4j
public class BookingCancelledNotificationListener {

    private final BookingRepository bookingRepository;
    private final EmailNotificationClient email;
    private final WhatsAppNotificationClient whatsApp;

    public BookingCancelledNotificationListener(BookingRepository bookingRepository,
                                                EmailNotificationClient email,
                                                WhatsAppNotificationClient whatsApp) {
        this.bookingRepository = bookingRepository;
        this.email = email;
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
                log.info("Booking-cancel email sent bookingId={} ref={} refund={}",
                        booking.getId(), booking.getConfirmationNumber(), refund);
            } catch (RuntimeException ex) {
                log.warn("Booking-cancel email failed bookingId={} (booking still CANCELLED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        String phone = booking.getPhoneNumber();
        if (phone != null && !phone.isBlank()) {
            try {
                whatsApp.sendCustomNotification(phone, message);
                log.info("Booking-cancel WhatsApp sent bookingId={} ref={}",
                        booking.getId(), booking.getConfirmationNumber());
            } catch (RuntimeException ex) {
                log.warn("Booking-cancel WhatsApp failed bookingId={} (booking still CANCELLED): {}",
                        booking.getId(), ex.getMessage());
            }
        }

        if ((emailAddr == null || emailAddr.isBlank()) && (phone == null || phone.isBlank())) {
            log.warn("BookingCancelled listener: no email or phone on booking {} — no cancel notice sent",
                    booking.getConfirmationNumber());
        }
    }

    private String buildCancellationText(Booking booking, boolean refund) {
        String ref = booking.getConfirmationNumber();
        if (refund) {
            return "Your booking " + ref + " has been cancelled. If a payment was taken, your refund is being "
                    + "processed — please allow a few business days. Questions? Contact InnBucks support.\n\n"
                    + "— The InnBucks Team";
        }
        return "Your booking " + ref + " has been cancelled and your reserved seats released. "
                + "You can book again anytime.\n\n— The InnBucks Team";
    }
}
