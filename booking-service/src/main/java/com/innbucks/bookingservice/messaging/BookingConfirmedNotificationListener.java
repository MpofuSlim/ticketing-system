package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.config.AsyncConfig;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.service.TicketDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
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
 *
 * <p><b>Async, and deliberately NOT {@code @Transactional}.</b> This used to
 * run synchronously on the confirming thread — payment-service's confirm
 * request — under {@code REQUIRES_NEW}: the committed transaction's connection
 * is still held while AFTER_COMMIT listeners run, so every confirm pinned TWO
 * pooled connections for the whole WhatsApp/email fan-out, and a slow gateway
 * held payment-service's call open with them. It now runs on
 * {@link AsyncConfig#TICKET_DELIVERY_EXECUTOR}; the booking is read with
 * {@code findByIdWithItems} (one short read, the same one the manual resend
 * uses) and delivery holds no transaction and no connection. Do not add
 * {@code @Transactional} back: it would hold a connection across every send.
 *
 * <p>The whole booking is still ONE sequential {@code deliver} call — attendee
 * sends run before the purchaser's receipt, which reports their outcomes, so
 * the per-ticket sends must not be split across threads.
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

    @Async(AsyncConfig.TICKET_DELIVERY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingDomainEvent.BookingConfirmed event) {
        Booking booking = bookingRepository.findByIdWithItems(event.bookingId()).orElse(null);
        if (booking == null) {
            log.warn("BookingConfirmed listener: booking not found bookingId={} — skipping notifications",
                    event.bookingId());
            return;
        }
        ticketDeliveryService.deliver(booking);
    }
}
