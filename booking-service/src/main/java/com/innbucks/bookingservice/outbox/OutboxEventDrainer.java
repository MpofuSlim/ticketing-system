package com.innbucks.bookingservice.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Drains {@link OutboxEvent} rows to Kafka. Runs every 5 seconds by default
 * — fast enough that downstream consumers (loyalty, analytics,
 * notifications) see booking events within seconds of the booking
 * committing, even though the wire-side send is now async to the request.
 *
 * <p>ShedLock-guarded so only one pod runs each tick. Without that, every
 * replica would pull the same {@code findDue} slice and double-send each
 * row — duplicates downstream, wasted Kafka producer load. The whole
 * point of the outbox is exactly-once-ish delivery (we tolerate the
 * very-occasional duplicate from a tick that publishes-then-crashes
 * before marking the row, but we MUST NOT have N pods racing on every
 * tick).
 *
 * <p>Publishes two gauges + the queue-depth signal Prometheus alerts on:
 *   {@code booking.event_outbox.pending}      — degraded-Kafka signal
 *   {@code booking.event_outbox.giving_up}    — operator-attention signal
 */
@Slf4j
@Component
public class OutboxEventDrainer {

    private final OutboxEventRepository repository;
    private final OutboxEventService service;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxEventDrainer(OutboxEventRepository repository,
                              OutboxEventService service,
                              MeterRegistry meterRegistry,
                              @Value("${app.event-outbox.batch-size:200}") int batchSize,
                              @Value("${app.event-outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.service = service;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;

        Gauge.builder("booking.event_outbox.pending",
                        () -> repository.countByStatus(OutboxEvent.Status.pending))
                .description("Outbox rows awaiting publication to Kafka")
                .register(meterRegistry);
        Gauge.builder("booking.event_outbox.giving_up",
                        () -> repository.countByStatus(OutboxEvent.Status.giving_up))
                .description("Outbox rows that exhausted max-attempts and need operator attention")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.event-outbox.interval-ms:5000}",
            initialDelayString = "${app.event-outbox.initial-delay-ms:10000}")
    @SchedulerLock(name = "OutboxEventDrainer.drain",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT1S")
    public void drain() {
        List<OutboxEvent> due = repository.findDue(LocalDateTime.now(ZoneOffset.UTC), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return;
        }
        log.debug("Draining {} event_outbox row(s) (batch limit {})", due.size(), batchSize);
        for (OutboxEvent row : due) {
            try {
                service.attemptPublish(row, maxAttempts);
            } catch (Exception ex) {
                // attemptPublish is itself @Transactional and catches its
                // own send failures; this is the last-resort guard so a
                // single corrupt row can't take down the whole batch.
                log.error("Unexpected error draining event_outbox row id={}", row.getId(), ex);
            }
        }
    }
}
