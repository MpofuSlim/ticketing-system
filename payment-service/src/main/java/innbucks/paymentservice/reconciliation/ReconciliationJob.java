package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled scan over the {@code transactions} ledger that surfaces rows
 * still in {@code PENDING} long after they should have flipped to
 * {@code SUCCEEDED} or {@code FAILED}. The class of bug this catches:
 * Oradian-success-but-local-DB-write-failed — payment-service called
 * Oradian, Oradian moved the money, then the markSucceeded write blew
 * up (DB blip, JVM crash, etc.) and the row never got out of PENDING.
 * The ledger exists specifically so these don't vanish; this job is
 * what surfaces them to operators before drift accumulates silently.
 *
 * <p>v1 is observe-only: log loudly + bump a Prometheus counter. No
 * auto-flip to {@code FAILED} because a stale PENDING might actually
 * be a successful Oradian transfer we just didn't confirm — flipping
 * it would tell the customer the transfer failed when their money
 * already moved. Resolution is operator-driven (check Oradian admin
 * console, fix via SQL or a future admin endpoint).
 *
 * <p>Alerting: any non-zero value of {@code payment.transactions.stale_pending}
 * across two scrape windows is a page. A single row needs operator
 * eyes; a sustained drip means payment-service is failing to mark
 * PENDING rows {@code SUCCEEDED}/{@code FAILED} systemically.
 */
@Component
@Slf4j
public class ReconciliationJob {

    private final TransactionRepository repository;
    private final PaymentMetrics metrics;
    private final Duration stalePendingThreshold;
    private final int batchSize;

    public ReconciliationJob(
            TransactionRepository repository,
            PaymentMetrics metrics,
            @Value("${payment-service.reconciliation.stale-pending-threshold:PT5M}") Duration stalePendingThreshold,
            @Value("${payment-service.reconciliation.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.metrics = metrics;
        this.stalePendingThreshold = stalePendingThreshold;
        this.batchSize = batchSize;
    }

    /**
     * Scan interval defaults to 1 minute — overridable in application.yaml
     * via {@code payment-service.reconciliation.scan-interval}. Fixed-delay
     * (not fixed-rate) so a long scan on one cluster doesn't pile up
     * overlapping invocations.
     */
    @Scheduled(fixedDelayString = "${payment-service.reconciliation.scan-interval:PT1M}")
    public void scan() {
        Instant cutoff = Instant.now().minus(stalePendingThreshold);
        List<Transaction> stale = repository.findStalePending(cutoff, PageRequest.of(0, batchSize));
        if (stale.isEmpty()) {
            log.debug("Reconciliation scan found no stale PENDING rows (threshold={})",
                    stalePendingThreshold);
            return;
        }

        // Loud at WARN per row so the operator's log search can pull them
        // out by ID without doing the math. Includes the full age in
        // seconds — useful for triaging whether the system is stuck for
        // minutes (DB blip recovering) or for hours (real outage).
        Instant now = Instant.now();
        for (Transaction tx : stale) {
            long ageSeconds = Duration.between(tx.getCreatedAt(), now).toSeconds();
            log.warn("Reconciliation found stale PENDING txId={} type={} src={} dst={} amount={} ageSeconds={}",
                    tx.getId(), tx.getTransactionType(),
                    tx.getSourceAccountId(), tx.getDestinationAccountId(),
                    tx.getAmount(), ageSeconds);
            metrics.incStalePendingTransaction(
                    tx.getTransactionType() == null ? null : tx.getTransactionType().name());
        }

        if (stale.size() == batchSize) {
            // The page was full — there may be more rows behind it that
            // this scan didn't see. We'll catch them on the next scan,
            // but mark loudly so operators know we're shedding load.
            log.warn("Reconciliation scan hit batch cap ({}); more stale PENDING rows likely behind it. " +
                    "Bump payment-service.reconciliation.batch-size or investigate systemic stalling.",
                    batchSize);
        }
    }
}
