package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Auto-cancels PENDING bookings whose seat hold has lapsed.
 *
 * <p>Runs on every booking-service replica every {@code app.booking.expiration-poll-interval-ms}
 * milliseconds (default 30s). With multiple replicas the same expired booking
 * may be processed twice; the {@link BookingRepository#findExpiredPending}
 * query plus the status check below make the second update a no-op
 * (idempotent). ShedLock now wraps the tick (see {@link com.innbucks.bookingservice.config.SchedulerLockConfig})
 * so only one leader runs each tick — even though the work is idempotent,
 * the duplicate query traffic + lock contention from N replicas hitting
 * the same rows is wasteful.
 *
 * <p>The job intentionally does NOT call seat-service to release seat status
 * back to AVAILABLE; today seat-service's seats table is decoupled from
 * booking-service's bookings (booking-service is the source of truth for what's
 * held). If seat-service status sync is added later, hook it in here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingExpirationService {

    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${app.booking.expiration-poll-interval-ms:30000}")
    @SchedulerLock(name = "BookingExpirationService.expirePending",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT15S")
    @Transactional
    public void expirePending() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Booking> expired = bookingRepository.findExpiredPending(now);
        if (expired.isEmpty()) {
            return;
        }
        log.info("Expiration tick — cancelling {} pending booking(s) past hold", expired.size());
        for (Booking booking : expired) {
            // Re-check inside the transaction in case another replica or the
            // payment endpoint mutated the row in between query and update.
            if (booking.getStatus() != Booking.BookingStatus.PENDING) {
                continue;
            }
            booking.setStatus(Booking.BookingStatus.CANCELLED);
            booking.setExpiresAt(null);
            bookingRepository.save(booking);
            eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));
            log.info("Booking auto-cancelled, hold expired bookingId={} userEmail={}",
                    booking.getId(), booking.getUserEmail());
        }
    }
}
