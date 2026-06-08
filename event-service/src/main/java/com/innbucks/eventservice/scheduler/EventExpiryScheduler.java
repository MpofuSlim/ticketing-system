package com.innbucks.eventservice.scheduler;

import com.innbucks.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Sweeps events whose endDateTime has passed and flips active=false on them so
 * they stop appearing in customer-facing listings. Cadence is configurable via
 * {@code app.event-expiry.cron}; the default is every 30 minutes, which caps
 * the lag between an event ending and the active flag flipping at ~30 min.
 *
 * <p>The listing queries also filter {@code endDateTime > :now} at read time
 * (see {@link EventRepository}), so customers never see an expired event even
 * between scheduler ticks. This job exists to keep the persisted {@code active}
 * flag in sync — useful for analytics and admin views that filter on it.
 *
 * <p>The UPDATE is naturally idempotent ({@code WHERE active=true} short-circuits
 * any second pass), so it is safe to run on multiple event-service replicas
 * without distributed locking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventExpiryScheduler {

    private final EventRepository eventRepository;

    @Scheduled(cron = "${app.event-expiry.cron:0 0/30 * * * *}", zone = "UTC")
    @Transactional
    public void expirePassedEvents() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int count = eventRepository.deactivateExpiredEvents(now, now);
        if (count > 0) {
            log.info("Event expiry job: deactivated {} past event(s) at {}", count, now);
        } else {
            log.debug("Event expiry job: no expired events found at {}", now);
        }
    }
}
