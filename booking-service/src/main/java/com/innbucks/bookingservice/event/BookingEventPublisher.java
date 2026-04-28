package com.innbucks.bookingservice.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges Spring's in-process ApplicationEvents to Kafka. The service layer
 * publishes {@link BookingDomainEvent}s via ApplicationEventPublisher inside
 * the transaction; this listener fires AFTER_COMMIT so a rolled-back booking
 * never produces a ghost event. Kafka send failures are logged — recovery
 * (replay / outbox) is out of scope for this introduction.
 */
@Component
@Slf4j
public class BookingEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String createdTopic;
    private final String confirmedTopic;
    private final String cancelledTopic;

    public BookingEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.events.booking-created-topic}") String createdTopic,
            @Value("${app.events.booking-confirmed-topic}") String confirmedTopic,
            @Value("${app.events.booking-cancelled-topic}") String cancelledTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.createdTopic = createdTopic;
        this.confirmedTopic = confirmedTopic;
        this.cancelledTopic = cancelledTopic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(BookingDomainEvent.BookingCreated event) {
        send(createdTopic, event.bookingId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(BookingDomainEvent.BookingConfirmed event) {
        send(confirmedTopic, event.bookingId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelled(BookingDomainEvent.BookingCancelled event) {
        send(cancelledTopic, event.bookingId().toString(), event);
    }

    private void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish event topic={} key={} cause={}",
                        topic, key, ex.toString());
                return;
            }
            log.debug("Published event topic={} key={} offset={}",
                    topic, key, result.getRecordMetadata().offset());
        });
    }
}
