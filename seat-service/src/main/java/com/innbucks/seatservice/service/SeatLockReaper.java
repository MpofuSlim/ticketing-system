package com.innbucks.seatservice.service;

import com.innbucks.seatservice.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sweeps seats whose LOCKED hold has elapsed and returns them to inventory.
 *
 * The Redis TTL on the lock owner is advisory — it does not, by itself,
 * revert the DB row. Without this sweep, any seat whose Redis key expires
 * (TTL elapsed, Redis restarted, etc.) stays LOCKED forever with no live
 * owner, permanently removing it from the bookable pool. The DB column
 * {@code lock_expires_at} (V3 migration) is the authoritative deadline.
 *
 * Each candidate is processed in its own transaction (via
 * {@link SeatService#releaseStaleLock(UUID)} with REQUIRES_NEW) so one row
 * holding a pessimistic lock cannot stall the entire batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.seat-lock-reaper.enabled", havingValue = "true", matchIfMissing = true)
public class SeatLockReaper {

    private final SeatRepository seatRepository;
    private final SeatService seatService;

    @Value("${app.seat-lock-reaper.batch-size:200}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${app.seat-lock-reaper.interval-ms:60000}",
            initialDelayString = "${app.seat-lock-reaper.initial-delay-ms:30000}"
    )
    public void reap() {
        List<UUID> candidates = seatRepository.findExpiredLockIds(
                LocalDateTime.now(),
                PageRequest.of(0, batchSize)
        );
        if (candidates.isEmpty()) {
            log.debug("Reaper found no expired locks");
            return;
        }

        log.info("Reaper found {} expired seat locks (batch limit {})", candidates.size(), batchSize);
        int released = 0;
        int skipped = 0;
        for (UUID seatId : candidates) {
            try {
                if (seatService.releaseStaleLock(seatId)) {
                    released++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // Don't let one stuck row kill the rest of the batch.
                log.warn("Reaper failed to release lock seatId={}", seatId, e);
                skipped++;
            }
        }
        log.info("Reaper batch complete released={} skipped={}", released, skipped);
    }
}
