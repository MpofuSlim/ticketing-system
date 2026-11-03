package com.innbucks.bookingservice.answers;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Deletes buyers' answers to booking questions once their event ended more
 * than {@code app.booking.answers-retention} ago (90 days; V32). The terms a
 * buyer accepted are a consent record and stay.
 *
 * <p>A booking stores the date its answers are due ({@code answers_purge_after},
 * the event's end + 90 days, as known at booking). Events move, so before
 * deleting anything the job re-reads the event's end LIVE from event-service:
 * <ul>
 *   <li>still due by the live end: the event's answers are deleted, in one
 *       short transaction, and the bookings stamped {@code answers_purged_at};</li>
 *   <li>the event moved later: the date is pushed back, nothing deleted;</li>
 *   <li>event-service no longer knows the event (deleted): deleted by the
 *       stored date;</li>
 *   <li>event-service could not be asked: skipped, tried again tomorrow. A day
 *       late beats deleting a postponed event's answers.</li>
 * </ul>
 * The remote read runs with no transaction open. Daily under ShedLock, so one
 * replica runs it; the deletes are idempotent if two ever did.
 */
@Component
@Slf4j
public class BookingAnswersRetentionJob {

    /** Events handled per run; the rest wait for the next day. */
    static final int EVENTS_PER_RUN = 200;

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final ObjectProvider<EventServiceClient> eventClient;
    private final TransactionTemplate tx;
    private final String internalToken;
    private final Duration retention;

    public BookingAnswersRetentionJob(BookingRepository bookingRepository,
                                      BookingItemRepository bookingItemRepository,
                                      ObjectProvider<EventServiceClient> eventClient,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${innbucks.internal-api-token:}") String internalToken,
                                      @Value("${app.booking.answers-retention:P90D}") String retention) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.eventClient = eventClient;
        this.tx = new TransactionTemplate(transactionManager);
        this.internalToken = internalToken;
        this.retention = Duration.parse(retention.strip());
    }

    @Scheduled(cron = "${app.booking.answers-retention-cron:0 40 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "BookingAnswersRetentionJob.purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        purgeDue(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** One run as of {@code now}; returns how many events' answers were deleted. */
    public int purgeDue(LocalDateTime now) {
        List<UUID> due = bookingRepository.findEventIdsWithAnswersDue(now, EVENTS_PER_RUN);
        int purged = 0;
        for (UUID eventId : due) {
            try {
                if (handle(eventId, now)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("Booking answers retention failed for one event, skipped eventId={} reason={}",
                        eventId, e.toString());
            }
        }
        if (!due.isEmpty()) {
            log.info("Booking answers retention: {} event(s) due, {} deleted", due.size(), purged);
        }
        return purged;
    }

    private boolean handle(UUID eventId, LocalDateTime now) {
        EventSalesWindowDTO window = liveWindow(eventId);
        if (window == null || window.getStatus() == null) {
            log.warn("Booking answers retention: event-service could not be asked, skipped eventId={}", eventId);
            return false;
        }
        if (!EventSalesWindowDTO.NOT_FOUND.equals(window.getStatus()) && window.getEndDateTime() != null) {
            LocalDateTime due = window.getEndDateTime().plus(retention);
            if (due.isAfter(now)) {
                tx.executeWithoutResult(s -> bookingRepository.rescheduleAnswersPurge(eventId, due));
                log.info("Booking answers retention: the event moved, deletion moved to {} eventId={}", due, eventId);
                return false;
            }
        }
        Integer items = tx.execute(s -> {
            int cleared = bookingItemRepository.clearAnswersForEvent(eventId);
            bookingRepository.markAnswersPurged(eventId, now);
            return cleared;
        });
        log.info("Booking answers deleted eventId={} tickets={}", eventId, items);
        return true;
    }

    private EventSalesWindowDTO liveWindow(UUID eventId) {
        EventServiceClient client = eventClient.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            ApiResult<EventSalesWindowDTO> answer = client.salesWindow(eventId, internalToken);
            return answer == null ? null : answer.getData();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
