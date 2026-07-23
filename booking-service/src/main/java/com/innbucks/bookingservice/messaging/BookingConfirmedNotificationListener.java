package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.service.TicketDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers the customer's tickets once a booking is confirmed. Fires on
 * {@link BookingDomainEvent.BookingConfirmed} AFTER the booking transaction
 * commits (idempotent confirm = exactly one event per real confirmation).
 *
 * <p>The actual channel fan-out (plain-text email + one WhatsApp QR template
 * send per ticket, each independent best-effort) lives in
 * {@link TicketDeliveryService} — shared with the manual organizer/admin
 * resend endpoint so both paths deliver identically.
 */
@Component
@Slf4j
public class BookingConfirmedNotificationListener {

    private final BookingRepository bookingRepository;
    private final TicketDeliveryService ticketDeliveryService;

    public BookingConfirmedNotificationListener(
            BookingRepository bookingRepository,
            TicketDeliveryService ticketDeliveryService) {
        this.bookingRepository = bookingRepository;
        this.ticketDeliveryService = ticketDeliveryService;
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
        ticketDeliveryService.deliver(booking);
    }
}
