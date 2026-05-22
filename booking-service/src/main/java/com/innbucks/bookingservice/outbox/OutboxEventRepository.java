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
}
