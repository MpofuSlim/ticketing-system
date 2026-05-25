package com.innbucks.bookingservice.loyalty;

import com.innbucks.bookingservice.client.LoyaltyServiceClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists failed {@code loyalty.earn} attempts to {@code loyalty_earn_retry}
 * so {@link LoyaltyEarnRetryJob} can retry them later. Used from
 * {@link com.innbucks.bookingservice.service.BookingService#applyLoyalty}
 * — every place that used to {@code catch (Exception ex) { log.warn(...) }}
 * now calls {@link #enqueue} so the side-effect isn't lost.
 *
 * <p>Why a separate service from the job? Two reasons:
 * <ol>
 *   <li>The enqueue runs inside the booking-confirm transaction; the
 *       drainer runs inside its own per-row transaction. Keeping them in
 *       different beans makes the propagation boundary explicit.</li>
 *   <li>The job depends on a live LoyaltyServiceClient (to call
 *       loyalty.earn on each retry). Forcing every booking-confirm to
 *       pull in that dependency would tangle wiring.</li>
 * </ol>
 */
@Slf4j
@Service
public class LoyaltyEarnRetryService {

    private final LoyaltyEarnRetryRepository repository;
    private final Counter queuedCounter;

    public LoyaltyEarnRetryService(LoyaltyEarnRetryRepository repository,
                                   MeterRegistry meterRegistry) {
        this.repository = repository;
        // Increments per enqueue — a sudden spike means loyalty-service
        // is degraded; pair with the queue-depth gauge LoyaltyEarnRetryJob
        // publishes for the operator dashboard.
        this.queuedCounter = Counter.builder("booking.loyalty_earn_retry.queued")
                .description("Failed loyalty.earn attempts queued for retry")
                .register(meterRegistry);
    }

    /**
     * Caller is responsible for the parent transaction (confirmBooking).
     * The new row is INSERTed in that transaction so a rolled-back confirm
     * doesn't leave a stray retry row.
     */
    public void enqueue(UUID bookingId,
                        String customerEmail,
                        String tenantId,
                        BigDecimal cashAmount,
                        String reference,
                        Throwable cause) {
        LoyaltyEarnRetry row = LoyaltyEarnRetry.builder()
                .bookingId(bookingId)
                .customerEmail(customerEmail)
                .tenantId(tenantId)
                .cashAmount(cashAmount)
                .reference(reference)
                .lastError(truncate(cause))
                .nextAttemptAt(LocalDateTime.now(ZoneOffset.UTC))
                .status(LoyaltyEarnRetry.Status.pending)
                .build();
        repository.save(row);
        queuedCounter.increment();
        log.warn("Queued loyalty.earn for retry bookingId={} cashAmount={} reason={}",
                bookingId, cashAmount, row.getLastError());
    }

    @Transactional
    public void attempt(LoyaltyEarnRetry row, LoyaltyServiceClient loyalty, int maxAttempts) {
        row.setAttempts(row.getAttempts() + 1);
        try {
            loyalty.earn(com.innbucks.bookingservice.dto.LoyaltyEarnRequest.builder()
                    .customerId(row.getCustomerEmail())
                    .tenantId(row.getTenantId())
                    .cashAmount(row.getCashAmount())
                    .reference(row.getReference())
                    .build());
            row.setStatus(LoyaltyEarnRetry.Status.succeeded);
            row.setLastError(null);
            repository.save(row);
            log.info("Loyalty earn retry succeeded bookingId={} attempts={}",
                    row.getBookingId(), row.getAttempts());
        } catch (Exception ex) {
            row.setLastError(truncate(ex));
            if (row.getAttempts() >= maxAttempts) {
                row.setStatus(LoyaltyEarnRetry.Status.giving_up);
                log.error("Loyalty earn giving up after {} attempts bookingId={} reason={}",
                        row.getAttempts(), row.getBookingId(), row.getLastError());
            } else {
                // Exponential backoff: 1m, 2m, 4m, 8m, 16m, 32m, 64m...
                long delaySeconds = 60L * (1L << Math.min(row.getAttempts(), 10));
                row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds));
                log.warn("Loyalty earn retry failed bookingId={} attempts={} nextAttemptAt={} reason={}",
                        row.getBookingId(), row.getAttempts(), row.getNextAttemptAt(), row.getLastError());
            }
            repository.save(row);
        }
    }

    private static String truncate(Throwable cause) {
        if (cause == null) {
            return null;
        }
        String msg = cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() == null ? "" : cause.getMessage());
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
