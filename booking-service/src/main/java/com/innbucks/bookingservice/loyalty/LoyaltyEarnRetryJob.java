package com.innbucks.bookingservice.loyalty;

import com.innbucks.bookingservice.client.LoyaltyServiceClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Drains {@code loyalty_earn_retry} every minute. Each row gets at most one
 * loyalty.earn call per tick — successes flip {@code status=succeeded},
 * failures bump {@code attempts} and push out {@code next_attempt_at} with
 * exponential backoff.
 *
 * <p>ShedLock-guarded so only one pod runs each tick. Without that, every
 * replica would call loyalty.earn for the same row simultaneously,
 * potentially double-crediting the customer's wallet — the inverse of
 * what the retry table exists to prevent.
 *
 * <p>The Micrometer gauges this bean publishes feed the operator
 * dashboard: queue depth ("how degraded is loyalty right now?") and
 * giving-up depth ("how many rows need manual reconciliation?"). Prometheus
 * alerts on the giving-up gauge so persistent failures don't sit silently
 * in the table.
 */
@Slf4j
@Component
public class LoyaltyEarnRetryJob {

    private final LoyaltyEarnRetryRepository repository;
    private final LoyaltyEarnRetryService retryService;
    // ObjectProvider so this bean can boot even if LoyaltyServiceClient
    // isn't on the classpath in some test profiles. Lazy lookup mirrors
    // the pattern BookingService already uses for the same client.
    private final ObjectProvider<LoyaltyServiceClient> loyaltyClientProvider;
    private final int batchSize;
    private final int maxAttempts;

    public LoyaltyEarnRetryJob(LoyaltyEarnRetryRepository repository,
                               LoyaltyEarnRetryService retryService,
                               ObjectProvider<LoyaltyServiceClient> loyaltyClientProvider,
                               MeterRegistry meterRegistry,
                               @Value("${app.loyalty-earn-retry.batch-size:50}") int batchSize,
                               @Value("${app.loyalty-earn-retry.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.retryService = retryService;
        this.loyaltyClientProvider = loyaltyClientProvider;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;

        // Queue-depth gauges. Cheap COUNT(*) on the status-indexed table
        // each scrape; far cheaper than a poll-and-log every tick.
        Gauge.builder("booking.loyalty_earn_retry.pending",
                        () -> repository.countByStatus(LoyaltyEarnRetry.Status.pending))
                .description("Number of failed loyalty.earn attempts awaiting retry")
                .register(meterRegistry);
        Gauge.builder("booking.loyalty_earn_retry.giving_up",
                        () -> repository.countByStatus(LoyaltyEarnRetry.Status.giving_up))
                .description("Number of loyalty.earn attempts that exceeded max-attempts and require operator action")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.loyalty-earn-retry.interval-ms:60000}",
            initialDelayString = "${app.loyalty-earn-retry.initial-delay-ms:30000}")
    @SchedulerLock(name = "LoyaltyEarnRetryJob.drain",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT30S")
    public void drain() {
        LoyaltyServiceClient loyalty = loyaltyClientProvider.getIfAvailable();
        if (loyalty == null) {
            log.debug("LoyaltyServiceClient not available; skipping retry drain");
            return;
        }
        List<LoyaltyEarnRetry> due = repository.findDue(LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            log.debug("No loyalty_earn_retry rows due");
            return;
        }
        log.info("Draining {} loyalty_earn_retry rows (batch limit {})", due.size(), batchSize);
        for (LoyaltyEarnRetry row : due) {
            try {
                retryService.attempt(row, loyalty, maxAttempts);
            } catch (Exception ex) {
                // attempt() is itself @Transactional and catches its own
                // exceptions; this is the last-resort guard so one stuck row
                // can't take down the batch.
                log.error("Unexpected error draining loyalty_earn_retry row id={}", row.getId(), ex);
            }
        }
    }
}
