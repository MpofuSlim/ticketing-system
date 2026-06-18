package com.innbucks.bookingservice.loyalty;

import com.innbucks.bookingservice.client.LoyaltyServiceClient;
import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists failed {@code loyalty.earn} attempts to {@code loyalty_earn_retry} so
 * {@link LoyaltyEarnRetryJob} can retry them. The enqueue runs inside the
 * booking-confirm transaction (a rolled-back confirm leaves no stray row); the
 * drainer runs in its own per-row transaction.
 */
@Slf4j
@Service
public class LoyaltyEarnRetryService {

    private final LoyaltyEarnRetryRepository repository;
    private final Counter queuedCounter;
    private final String internalToken;

    public LoyaltyEarnRetryService(LoyaltyEarnRetryRepository repository,
                                   MeterRegistry meterRegistry,
                                   @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.repository = repository;
        this.internalToken = internalToken;
        this.queuedCounter = Counter.builder("booking.loyalty_earn_retry.queued")
                .description("Failed loyalty.earn attempts queued for retry")
                .register(meterRegistry);
    }

    /** Caller owns the parent transaction (confirmBooking). */
    public void enqueue(UUID bookingId,
                        UUID organizerUuid,
                        String phoneNumber,
                        BigDecimal cashAmount,
                        String reference,
                        Throwable cause) {
        LoyaltyEarnRetry row = LoyaltyEarnRetry.builder()
                .bookingId(bookingId)
                .organizerUuid(organizerUuid)
                .phoneNumber(phoneNumber)
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
            loyalty.earn(LoyaltyEarnRequest.builder()
                    .organizerUuid(row.getOrganizerUuid())
                    .phoneNumber(row.getPhoneNumber())
                    .cashAmount(row.getCashAmount())
                    .reference(row.getReference())
                    .build(), internalToken);
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
