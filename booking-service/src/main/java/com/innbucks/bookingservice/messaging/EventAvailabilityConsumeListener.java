package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.AsyncConfig;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decrements the event's stored {@code availableTickets} in event-service once
 * a booking's confirmation has COMMITTED.
 *
 * <p>This used to be a Feign call inside {@code BookingService.confirmBooking}'s
 * transaction, so every confirm held a pooled connection across an
 * event-service round trip — for a value that is only a
 * display mirror: the oversell guard is booking-service's per-category
 * counter, and event-service's read-time enrichment subtracts confirmed items
 * anyway. Moving it after commit costs nothing in correctness and also stops a
 * rolled-back confirm from ever having decremented anything.
 *
 * <p>One call per REAL confirmation: {@link BookingDomainEvent.BookingConfirmed}
 * is published only on the PENDING -> CONFIRMED flip, never on the idempotent
 * replay of an already-CONFIRMED booking. The event carries the event id and
 * ticket count, so no booking read is needed here.
 *
 * <p>Best-effort, as before: a failure is logged and swallowed — the booking is
 * confirmed and committed whatever happens here. No {@code @Transactional}: it
 * touches no table.
 */
@Component
@Slf4j
public class EventAvailabilityConsumeListener {

    private final EventServiceClient eventServiceClient;
    private final String internalToken;

    public EventAvailabilityConsumeListener(
            EventServiceClient eventServiceClient,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.eventServiceClient = eventServiceClient;
        this.internalToken = internalToken;
    }

    @Async(AsyncConfig.TICKET_DELIVERY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingDomainEvent.BookingConfirmed event) {
        if (event.eventId() == null || event.ticketCount() <= 0) {
            return;
        }
        try {
            ApiResult<AvailabilityResponseDTO> envelope =
                    eventServiceClient.consumeAvailability(event.eventId(), event.ticketCount(), internalToken);
            AvailabilityResponseDTO data = envelope == null ? null : envelope.getData();
            if (data != null) {
                log.info("Decremented event availability eventId={} consumed={} remaining={} bookingId={}",
                        event.eventId(), event.ticketCount(), data.getAvailableTickets(), event.bookingId());
            } else {
                log.warn("Availability decrement returned no data eventId={} consumed={} bookingId={}",
                        event.eventId(), event.ticketCount(), event.bookingId());
            }
        } catch (RuntimeException ex) {
            // Never surfaces anywhere: the confirm has committed and returned.
            // event-service's read-time enrichment still subtracts confirmed
            // booking items, so its API response stays correct.
            log.warn("Failed to decrement event availability eventId={} count={} bookingId={} reason={}",
                    event.eventId(), event.ticketCount(), event.bookingId(), ex.getMessage());
        }
    }
}
