package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.CategoryInventoryRepository;
import com.innbucks.bookingservice.repository.ExpiryCandidate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Auto-cancels PENDING bookings whose seat hold has lapsed, and returns their
 * tickets to stock.
 *
 * <p><b>One short transaction per booking.</b> The sweep used to load every
 * lapsed hold and cancel them all in ONE transaction: a single failure rolled
 * back the whole batch (and the same failing row came back first every run,
 * so nothing behind it was ever cancelled), and every counter row it released
 * stayed locked until the batch committed, so the sweep held category locks
 * across bookings in no particular order. Now:
 * <ol>
 *   <li>the candidates are read with NO lock — PENDING, {@code expires_at}
 *       before the run's start, oldest first, a page of {@value #PAGE_SIZE}
 *       on an {@code (expires_at, id)} keyset cursor;</li>
 *   <li>each one is cancelled in a transaction of its own: its row locked
 *       {@code FOR UPDATE SKIP LOCKED} (a row a confirm or a cancel holds is
 *       left for the next run, never waited for), re-checked (still PENDING,
 *       still lapsed), its tickets released through {@link StockLedger} in the
 *       canonical order, and one bulk {@code UPDATE} moving it to CANCELLED.
 *       So each transaction takes the booking row, then counter rows in
 *       ascending order, and commits before the next booking starts: no lock
 *       outlives its booking;</li>
 *   <li>a booking whose transaction FAILS is rolled back, counted on
 *       {@code booking.expiry.skipped} (alert {@code ExpirySkipping}) and
 *       stepped over: the cursor moves past it, so it can never block the
 *       holds behind it. One that is merely locked, no longer PENDING or no
 *       longer lapsed is skipped without being counted; if it is still due,
 *       the next run takes it;</li>
 *   <li>the run stops after a short page or after {@link #RUN_BUDGET}, so a
 *       backlog drains over several runs rather than holding the ShedLock for
 *       minutes.</li>
 * </ol>
 *
 * <p>{@code BookingCancelled} is published inside each booking's transaction,
 * and every listener for it is {@code AFTER_COMMIT}: the cancellation notice
 * goes out only once that booking's cancellation has committed, and never for
 * one that rolled back. (Publishing after the template returned would be
 * dropped: a transactional listener ignores an event published with no
 * transaction open.) The notices themselves are unchanged.
 *
 * <p>ShedLock still makes one replica run each tick. {@code lockAtLeastFor}
 * is PT5S: a longer floor stretches the poll interval (at PT15S a 10 s poll
 * really ran every 20 s), and the per-row SKIP LOCKED makes an overlapping run
 * harmless anyway. {@code lockAtMostFor} stays PT5M, well above the 25 s run
 * budget.
 */
@Service
@Slf4j
public class BookingExpirationService {

    /** Candidates read per page. A shorter page means the backlog is drained. */
    static final int PAGE_SIZE = 200;

    /** Wall-clock budget of one run; the next run continues. */
    static final Duration RUN_BUDGET = Duration.ofSeconds(25);

    private enum Outcome { EXPIRED, SKIPPED, FAILED }

    private final BookingRepository bookingRepository;
    private final StockLedger stockLedger;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate perBooking;
    private final BookingMetrics metrics;

    // Package-private so tests can shrink a page or the budget.
    int pageSize = PAGE_SIZE;
    Duration runBudget = RUN_BUDGET;

    public BookingExpirationService(BookingRepository bookingRepository,
                                    CategoryInventoryRepository categoryInventoryRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    PlatformTransactionManager transactionManager,
                                    BookingMetrics metrics) {
        this.bookingRepository = bookingRepository;
        this.stockLedger = new StockLedger(categoryInventoryRepository);
        this.eventPublisher = eventPublisher;
        this.perBooking = TransactionPhases.readWrite(transactionManager);
        this.metrics = metrics;
    }

    // Deliberately NOT @Transactional: a transaction here would span every
    // booking of the run, which is exactly what this class exists to avoid.
    @Scheduled(fixedDelayString = "${app.booking.expiration-poll-interval-ms:30000}")
    @SchedulerLock(name = "BookingExpirationService.expirePending",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT5S")
    public void expirePending() {
        long started = System.nanoTime();
        // Bound from Java in UTC, like every timestamp this service writes.
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC);
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        ExpiryCandidate after = null;
        while (true) {
            List<ExpiryCandidate> page = after == null
                    ? bookingRepository.findExpiryCandidates(cutoff, Limit.of(pageSize))
                    : bookingRepository.findExpiryCandidatesAfter(cutoff, after.expiresAt(), after.id(),
                            Limit.of(pageSize));
            for (ExpiryCandidate candidate : page) {
                switch (expireOne(candidate.id())) {
                    case EXPIRED -> expired++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                }
                if (Duration.ofNanos(System.nanoTime() - started).compareTo(runBudget) >= 0) {
                    log.info("Expiry sweep stopped at its {}s budget — expired={} skipped={} failed={}; "
                                    + "the next run continues",
                            runBudget.toSeconds(), expired, skipped, failed);
                    return;
                }
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.get(page.size() - 1);
        }
        if (expired + skipped + failed > 0) {
            log.info("Expiry sweep done — expired={} skipped={} failed={} in {}ms", expired, skipped, failed,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    /**
     * Cancels one lapsed hold in a transaction of its own. Never throws: a
     * failure is rolled back, counted and reported as {@link Outcome#FAILED},
     * so the run carries on with the next booking.
     */
    private Outcome expireOne(UUID bookingId) {
        try {
            Outcome outcome = perBooking.execute(status -> {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                Booking booking = bookingRepository.lockByIdSkipLocked(bookingId).orElse(null);
                if (booking == null) {
                    // Held by a confirm, a cancel or a reversal right now (or
                    // gone). Not a failure; the next run sees how it ended.
                    log.debug("Expiry sweep skipped a locked booking bookingId={}", bookingId);
                    return Outcome.SKIPPED;
                }
                if (booking.getStatus() != Booking.BookingStatus.PENDING
                        || booking.getExpiresAt() == null
                        || !booking.getExpiresAt().isBefore(now)) {
                    // Confirmed, cancelled or extended since the candidate scan.
                    return Outcome.SKIPPED;
                }
                stockLedger.release(booking);
                if (bookingRepository.markExpired(bookingId, now) != 1) {
                    // Cannot happen under the row lock with the status checked
                    // above; if it ever does, keep the stock where it was.
                    status.setRollbackOnly();
                    log.warn("Expiry sweep found booking no longer PENDING at update bookingId={}", bookingId);
                    return Outcome.SKIPPED;
                }
                eventPublisher.publishEvent(BookingDomainEvent.BookingCancelled.of(booking));
                log.info("Booking auto-cancelled, hold expired bookingId={} userEmail={}",
                        booking.getId(), booking.getUserEmail());
                return Outcome.EXPIRED;
            });
            return outcome == null ? Outcome.SKIPPED : outcome;
        } catch (RuntimeException ex) {
            metrics.expirySkipped();
            log.warn("Expiry sweep could not cancel bookingId={}, rolled back and skipped: {}",
                    bookingId, ex.toString());
            return Outcome.FAILED;
        }
    }
}
