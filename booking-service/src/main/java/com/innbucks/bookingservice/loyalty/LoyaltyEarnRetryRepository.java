package com.innbucks.bookingservice.loyalty;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LoyaltyEarnRetryRepository extends JpaRepository<LoyaltyEarnRetry, UUID> {

    /**
     * Pull the next batch of retry candidates. Predicate matches the partial
     * index {@code idx_loyalty_earn_retry_pending} so each tick is one
     * index-only scan + heap fetches for the LIMIT slice.
     *
     * <p>Sorted by {@code nextAttemptAt} so the oldest backed-off rows go
     * first (FIFO is good enough; we don't priority-sort by booking
     * amount). Caller supplies the {@link Pageable} to cap the batch
     * size.
     */
    @Query("""
            SELECT r FROM LoyaltyEarnRetry r
             WHERE r.status = com.innbucks.bookingservice.loyalty.LoyaltyEarnRetry.Status.pending
               AND r.nextAttemptAt <= :now
             ORDER BY r.nextAttemptAt ASC
            """)
    List<LoyaltyEarnRetry> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Backed by {@code idx_loyalty_earn_retry_status} — used by the
     * "queue depth" metric the retry job emits each tick. A growing
     * pending depth is the alerting signal that loyalty-service is
     * degraded; growing giving_up depth requires operator attention.
     */
    long countByStatus(LoyaltyEarnRetry.Status status);
}
