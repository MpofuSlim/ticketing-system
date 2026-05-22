package com.innbucks.bookingservice.event;

import com.innbucks.bookingservice.outbox.OutboxEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges Spring's in-process ApplicationEvents to the {@code event_outbox}
 * table. The drainer ({@code OutboxEventDrainer}) is the half that actually
 * talks to Kafka.
 *
 * <p>Pre-V8 this class called {@code kafkaTemplate.send(...)} directly
 * inside an {@code @TransactionalEventListener(AFTER_COMMIT)} listener.
 * KafkaTemplate.send is async, so a broker outage during the send
 * callback log.warn'd and silently dropped the event — downstream
 * consumers (loyalty, analytics, notifications) diverged from the
 * booking-service source of truth with no operator signal.
 *
 * <p>Now the listener fires {@code BEFORE_COMMIT}, INSERTing an outbox
 * row into the SAME transaction as the booking write. If the outbox
 * INSERT fails, the booking transaction rolls back too — that's the
 * right outcome: a booking we can't publish an event for is worse than
 * a refused booking, because downstream state becomes silently wrong.
 *
 * <p>Outbox drainer takes over from there: every 5 seconds it pulls
 * pending rows and sends to Kafka with exponential backoff and a
 * giving-up threshold (alerted via Micrometer gauge).
 */
@Component
@Slf4j
public class BookingEventPublisher {

    private final OutboxEventService outboxEventService;
    private final String createdTopic;
    private final String confirmedTopic;
    private final String cancelledTopic;

    public BookingEventPublisher(
            OutboxEventService outboxEventService,
            @Value("${app.events.booking-created-topic}") String createdTopic,
            @Value("${app.events.booking-confirmed-topic}") String confirmedTopic,
            @Value("${app.events.booking-cancelled-topic}") String cancelledTopic) {
        this.outboxEventService = outboxEventService;
        this.createdTopic = createdTopic;
        this.confirmedTopic = confirmedTopic;
        this.cancelledTopic = cancelledTopic;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCreated(BookingDomainEvent.BookingCreated event) {
        enqueue(createdTopic, event.bookingId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onConfirmed(BookingDomainEvent.BookingConfirmed event) {
        enqueue(confirmedTopic, event.bookingId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCancelled(BookingDomainEvent.BookingCancelled event) {
        enqueue(cancelledTopic, event.bookingId().toString(), event);
    }

    private void enqueue(String topic, String key, Object payload) {
        outboxEventService.enqueue(topic, key, payload);
        log.debug("Outbox enqueued topic={} key={}", topic, key);
    }
}
