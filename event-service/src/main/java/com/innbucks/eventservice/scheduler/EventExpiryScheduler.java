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
 * Nightly job that flips active=false on every event whose scheduled dateTime
 * has passed. Runs at midnight (00:00) server time so stale events never
 * appear in /events/active on the next calendar day.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventExpiryScheduler {

    private final EventRepository eventRepository;

    @Scheduled(cron = "0 0 0 * * *")
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
