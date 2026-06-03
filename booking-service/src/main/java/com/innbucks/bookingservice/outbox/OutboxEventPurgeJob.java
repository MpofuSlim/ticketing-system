package com.innbucks.bookingservice.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Periodic purge of successfully-published {@link OutboxEvent} rows. Without
 * this the table grows unbounded — the drainer marks rows {@code published}
 * but never deletes them, and every booking lifecycle (create / confirm /
 * cancel) writes 1-3 events. At projected throughput the table would hit
 * O(100M) rows in a quarter, dragging every subsequent drainer SELECT down
 * with it (the partial pending index helps but heap bloat eventually wins).
 *
 * <p>Scope deliberately narrow:
 * <ul>
 *   <li>{@code pending} rows are never deleted — the drainer is still trying
 *       to publish them.</li>
 *   <li>{@code giving_up} rows are never deleted either — they're the
 *       operator-attention signal the {@code booking.event_outbox.giving_up}
 *       gauge alerts on. Auto-reaping them would erase the failures we said
 *       operators must look at. They stay until an operator manually drains
 *       them (re-queue or delete) after triage.</li>
 *   <li>Only {@code published} rows older than the retention window are
 *       eligible. Retention defaults to 7 days — enough lead time for a stuck
 *       downstream consumer to be noticed and the payload recovered before
 *       it goes.</li>
 * </ul>
 *
 * <p>ShedLock-guarded so only one pod purges per tick. The deletes themselves
 * are idempotent (by primary key) but parallel pods would still race on the
 * same batch and burn DB connections + WAL writes.
 *
 * <p>Each tick deletes at most {@code app.event-outbox.purge.batch-size} rows
 * (default 1000) so the per-tick transaction stays small. A sustained-backlog
 * catch-up therefore takes multiple ticks — fine, the table is not in the
 * request path.
 */
@Slf4j
@Component
public class OutboxEventPurgeJob {

    private final OutboxEventRepository repository;
    private final int batchSize;
    private final Duration retention;
    private final Counter purgedCounter;

    public OutboxEventPurgeJob(OutboxEventRepository repository,
                               MeterRegistry meterRegistry,
                               @Value("${app.event-outbox.purge.batch-size:1000}") int batchSize,
                               @Value("${app.event-outbox.purge.retention:P7D}") Duration retention) {
        this.repository = repository;
        this.batchSize = batchSize;
        this.retention = retention;
        this.purgedCounter = Counter.builder("booking.event_outbox.purged")
                .description("Published outbox rows deleted by the periodic purge job")
                .register(meterRegistry);
    }

    /**
     * Default interval 1h — small enough that steady-state backlog stays
     * bounded, large enough that the purge isn't competing with the drainer
     * for connection-pool slots on every tick. Initial delay gives the app a
     * minute to settle before the first scan, mirroring the drainer's own
     * startup pattern.
     */
    @Scheduled(fixedDelayString = "${app.event-outbox.purge.interval-ms:3600000}",
            initialDelayString = "${app.event-outbox.purge.initial-delay-ms:60000}")
    @SchedulerLock(name = "OutboxEventPurgeJob.purge",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1S")
    @Transactional
    public void purge() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(retention);
        List<UUID> ids = repository.findPublishedOlderThan(cutoff,
                PageRequest.of(0, batchSize));
        if (ids.isEmpty()) {
            log.debug("event_outbox purge: nothing to delete (cutoff={})", cutoff);
            return;
        }
        repository.deleteAllByIdInBatch(ids);
        purgedCounter.increment(ids.size());
        log.info("event_outbox purge: deleted {} published row(s) older than {} (cutoff={})",
                ids.size(), retention, cutoff);
    }
}
