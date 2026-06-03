package com.innbucks.bookingservice.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Pull the next batch of due outbox rows. Predicate matches the partial
     * index {@code idx_event_outbox_pending} so each tick is one index-only
     * scan + heap fetch for the LIMIT slice. Oldest-first so the drainer
     * doesn't starve back-pressured rows during sustained Kafka degradation.
     */
    @Query("""
            SELECT e FROM OutboxEvent e
             WHERE e.status = com.innbucks.bookingservice.outbox.OutboxEvent.Status.pending
               AND e.nextAttemptAt <= :now
             ORDER BY e.nextAttemptAt ASC
            """)
    List<OutboxEvent> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /** Backs the queue-depth gauge OutboxEventDrainer publishes each scrape. */
    long countByStatus(OutboxEvent.Status status);

    /**
     * Page of {@code published} row IDs older than {@code cutoff}, used by
     * {@link OutboxEventPurgeJob} to bound each purge batch. We return IDs
     * (not entities) because the caller only needs them to feed
     * {@code deleteAllByIdInBatch}, and IDs alone keep the hydrator out of
     * the loop. Oldest-first so a sustained backlog drains FIFO and the
     * partial index {@code idx_event_outbox_published_updated_at} (V11)
     * stays on the cheap ASC scan path.
     *
     * <p>Status filter is hard-coded to {@code published} — {@code giving_up}
     * rows must NEVER be purged here (they're the operator-attention signal),
     * and {@code pending} rows still belong to the drainer.
     */
    @Query("""
            SELECT e.id FROM OutboxEvent e
             WHERE e.status = com.innbucks.bookingservice.outbox.OutboxEvent.Status.published
               AND e.updatedAt < :cutoff
             ORDER BY e.updatedAt ASC
            """)
    List<UUID> findPublishedOlderThan(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
