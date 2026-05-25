package com.innbucks.bookingservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Outbox-side counterpart to {@code BookingEventPublisher} (which is now
 * the enqueue side) and {@link OutboxEventDrainer} (the publish side).
 * Holds the two operations that mutate {@link OutboxEvent} rows so the
 * publisher + drainer both have one place to look.
 *
 * <p>Why a separate service from the drainer? Same reason as the
 * loyalty-retry split — the drainer's drain() loop runs without a
 * transaction; each {@link #attemptPublish} call opens its own short
 * transaction so a stuck row doesn't hold a Hikari connection across
 * the whole batch.
 */
@Slf4j
@Service
public class OutboxEventService {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter givingUpCounter;

    public OutboxEventService(OutboxEventRepository repository,
                              KafkaTemplate<String, Object> kafkaTemplate,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        // Drainer side counters — operator dashboard: sustained drain throughput
        // and (more importantly) the rate at which rows hit max-attempts.
        this.publishedCounter = Counter.builder("booking.event_outbox.published")
                .description("Outbox rows successfully sent to Kafka")
                .register(meterRegistry);
        // Counter (transition) vs. Gauge (current depth) — Micrometer rejects
        // two meters of different types under the same name, so the counter
        // uses `gave_up` and the Gauge in OutboxEventDrainer uses `giving_up`.
        this.givingUpCounter = Counter.builder("booking.event_outbox.gave_up")
                .description("Per-transition counter: outbox rows that just exceeded max-attempts")
                .register(meterRegistry);
    }

    /**
     * Caller (BookingEventPublisher) is responsible for the enclosing
     * transaction — the row INSERT must land in the same tx as the
     * booking write so a rolled-back booking doesn't leak a row.
     * Serialisation runs synchronously (Jackson, in-memory) so any
     * marshal failure here rolls the booking back too — the right
     * outcome for an event that can't be encoded.
     */
    public OutboxEvent enqueue(String topic, String key, Object event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            // Wrap as a RuntimeException so the booking transaction rolls
            // back — better to refuse the booking than commit it without
            // a publishable event downstream.
            throw new IllegalStateException(
                    "Failed to serialise outbox event topic=" + topic + " class=" + event.getClass().getName(), ex);
        }
        OutboxEvent row = OutboxEvent.builder()
                .topic(topic)
                .eventKey(key)
                .eventClass(event.getClass().getName())
                .payload(payload)
                .nextAttemptAt(LocalDateTime.now(ZoneOffset.UTC))
                .status(OutboxEvent.Status.pending)
                .build();
        return repository.save(row);
    }

    /**
     * One drain attempt per row. Synchronous {@code KafkaTemplate.send.get()}
     * because we need the success/failure outcome before we can decide
     * whether to mark the row published or schedule a retry.
     *
     * <p>maxAttempts is a per-call budget — exceeded -> flip to giving_up
     * (alerted via {@link #givingUpCounter}).
     */
    @Transactional
    public void attemptPublish(OutboxEvent row, int maxAttempts) {
        row.setAttempts(row.getAttempts() + 1);
        try {
            Class<?> eventClass = Class.forName(row.getEventClass());
            Object event = objectMapper.readValue(row.getPayload(), eventClass);
            // .get() blocks for the broker ack. We're already inside the
            // drainer's per-row thread; the per-row transaction sits open
            // for at most the Kafka send latency.
            kafkaTemplate.send(row.getTopic(), row.getEventKey(), event).get();
            row.setStatus(OutboxEvent.Status.published);
            row.setLastError(null);
            repository.save(row);
            publishedCounter.increment();
            log.debug("Outbox row published id={} topic={} key={} attempts={}",
                    row.getId(), row.getTopic(), row.getEventKey(), row.getAttempts());
        } catch (Exception ex) {
            row.setLastError(truncate(ex));
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus(OutboxEvent.Status.giving_up);
                givingUpCounter.increment();
                log.error("Outbox row giving up after {} attempts id={} topic={} reason={}",
                        row.getAttempts(), row.getId(), row.getTopic(), row.getLastError());
            } else {
                // Exponential backoff. Shorter base than loyalty-retry
                // (5s, 10s, 20s, 40s, ...) because event publication
                // latency matters more than loyalty-earn — downstream
                // consumers (notifications, analytics) want near-real-time.
                long delaySeconds = 5L * (1L << Math.min(row.getAttempts(), 10));
                row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds));
                log.warn("Outbox row publish failed id={} topic={} attempts={} nextAttemptAt={} reason={}",
                        row.getId(), row.getTopic(), row.getAttempts(),
                        row.getNextAttemptAt(), row.getLastError());
            }
            repository.save(row);
        }
    }

    private static String truncate(Throwable cause) {
        if (cause == null) {
            return null;
        }
        String msg = cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() == null ? "" : cause.getMessage());
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
