package com.innbucks.loyaltyservice.scheduler;

import com.innbucks.loyaltyservice.entity.OradianSyncTransaction;
import com.innbucks.loyaltyservice.repository.OradianSyncTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves {@code OradianSyncTransaction} rows that have been stuck in
 * PENDING beyond the grace window.
 *
 * <p>In the current synchronous sync-first model
 * ({@code OradianSyncService.syncDelta}) a PENDING row should never
 * actually persist: it's inserted on the calling transaction which
 * either commits the row as SUCCEEDED (or as FAILED via REQUIRES_NEW)
 * or rolls back entirely. A persisted PENDING row therefore signals
 * one of:
 *
 * <ul>
 *   <li>JVM crash between insert and finalisation</li>
 *   <li>A future async / outbox-style sync path that legitimately
 *       leaves rows PENDING while a worker drains them</li>
 *   <li>A bug — useful to surface here rather than silently leave the
 *       row dangling</li>
 * </ul>
 *
 * <p>Resolution policy: mark the row as FAILED with
 * {@code failure_code=RECONCILED_UNKNOWN_OUTCOME}. We deliberately do
 * NOT try to re-issue the Oradian call to determine the "true" outcome
 * — that would require reconstructing the original payload, and the
 * customer's local mutation already rolled back when the call timed
 * out, so a delayed local re-apply would conflict with whatever
 * subsequent earns / spends the customer has run. The
 * {@code OradianBalanceAuditJob} is the authoritative drift-detector.
 *
 * <p>Schedule + grace window configurable via:
 * <pre>
 * loyalty.oradian-sync.reconciliation-fixed-delay  (default: PT5M)
 * loyalty.oradian-sync.reconciliation-grace        (default: PT2M)
 * </pre>
 *
 * <p>ShedLock-coordinated so a multi-replica deployment runs the scan
 * on one instance per tick.
 */
@Component
public class OradianSyncReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OradianSyncReconciliationJob.class);

    private final boolean enabled;
    private final Duration graceWindow;
    private final OradianSyncTransactionRepository syncRepo;
    private final Counter reconciledCounter;

    public OradianSyncReconciliationJob(
            @Value("${loyalty.oradian-sync.enabled:false}") boolean enabled,
            @Value("${loyalty.oradian-sync.reconciliation-grace:PT2M}") Duration graceWindow,
            OradianSyncTransactionRepository syncRepo,
            MeterRegistry registry) {
        this.enabled = enabled;
        this.graceWindow = graceWindow;
        this.syncRepo = syncRepo;
        this.reconciledCounter = Counter.builder("loyalty.oradian_sync.reconciled")
                .description("OradianSyncTransaction rows finalised by the reconciliation job")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${loyalty.oradian-sync.reconciliation-fixed-delay:PT5M}",
            initialDelayString = "PT30S")
    @SchedulerLock(name = "loyaltyOradianSyncReconciliation",
            lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    @Transactional
    public void reconcile() {
        if (!enabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(graceWindow);
        List<OradianSyncTransaction> stale = syncRepo.findStalePending(cutoff);
        if (stale.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (OradianSyncTransaction tx : stale) {
            tx.setStatus(OradianSyncTransaction.Status.FAILED);
            tx.setFailureCode("RECONCILED_UNKNOWN_OUTCOME");
            tx.setFailureMessage("Reconciled by sweeper after grace window; outcome on Oradian " +
                    "is unknown. Balance-audit job will surface any drift.");
            tx.setCompletedAt(now);
            reconciledCounter.increment();
            log.warn("Reconciled stale PENDING OradianSyncTransaction id={} walletId={} " +
                            "createdAt={} delta={}",
                    tx.getId(), tx.getWalletId(), tx.getCreatedAt(), tx.getDeltaPoints());
        }
        log.info("OradianSyncReconciliationJob finalised {} stale PENDING rows " +
                "(grace={}, cutoff={})", stale.size(), graceWindow, cutoff);
    }
}
