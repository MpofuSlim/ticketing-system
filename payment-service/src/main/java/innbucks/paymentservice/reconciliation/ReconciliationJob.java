package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.TransactionRepository;
import innbucks.paymentservice.service.PaymentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Scheduled scans over both money ledgers.
 *
 * <p><b>1. {@code transactions} (wallet transfers/withdrawals):</b> surfaces
 * rows still PENDING long after they should have flipped — the
 * Oradian-success-but-local-write-failed class of bug. Observe-only: log +
 * counter; resolution is operator-driven (see the original class notes).
 *
 * <p><b>2. {@code payment} (ticket-checkout debits):</b> two sweeps.
 * <ul>
 *   <li><b>Stale PENDING / IN_DOUBT:</b> rows past the staleness threshold.
 *       IN_DOUBT means an upstream call timed out — money MAY have moved.
 *       Observe-only today because the s2s gateway exposes no
 *       query-by-reference; the direct veengu Purchase flow adds
 *       {@code GET /paymentDetails/{token}/transaction} and upgrades this
 *       sweep to resolve-by-query. Never auto-fail: flipping an IN_DOUBT
 *       row to FAILED would tell the customer a payment failed when their
 *       money may already have moved.</li>
 *   <li><b>COMPLETED_UNCONFIRMED (self-heal):</b> money definitely moved but
 *       the booking confirm failed at payment time. Booking-side confirm is
 *       an idempotent replay, so this sweep RETRIES it; on success the row
 *       is promoted to SUCCEEDED. Rows that keep failing stay put, get
 *       logged at WARN with the booking-side reason, and bump the
 *       {@code payment.payments.unconfirmed_retry{outcome=still_failing}}
 *       counter — sustained drips there are customers debited without
 *       tickets, the loudest page this service owns.</li>
 * </ul>
 */
@Component
@Slf4j
public class ReconciliationJob {

    /** Ticket-payment states the staleness sweep watches (non-terminal). */
    private static final EnumSet<PaymentStatus> STALE_WATCH =
            EnumSet.of(PaymentStatus.PENDING, PaymentStatus.IN_DOUBT);

    private final TransactionRepository repository;
    private final PaymentRepository paymentRepository;
    private final PaymentRecordService paymentRecordService;
    private final BookingServiceClient bookingServiceClient;
    private final PaymentMetrics metrics;
    private final Duration stalePendingThreshold;
    private final int batchSize;

    public ReconciliationJob(
            TransactionRepository repository,
            PaymentRepository paymentRepository,
            PaymentRecordService paymentRecordService,
            BookingServiceClient bookingServiceClient,
            PaymentMetrics metrics,
            @Value("${payment-service.reconciliation.stale-pending-threshold:PT5M}") Duration stalePendingThreshold,
            @Value("${payment-service.reconciliation.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.paymentRepository = paymentRepository;
        this.paymentRecordService = paymentRecordService;
        this.bookingServiceClient = bookingServiceClient;
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

    /** Ticket-payment ledger sweeps (stale watch + unconfirmed self-heal). */
    @Scheduled(fixedDelayString = "${payment-service.reconciliation.scan-interval:PT1M}")
    public void scanPayments() {
        sweepStalePayments();
        retryUnconfirmedBookings();
    }

    private void sweepStalePayments() {
        Instant cutoff = Instant.now().minus(stalePendingThreshold);
        List<Payment> stale = paymentRepository.findByStatusInAndCreatedAtBefore(
                STALE_WATCH, cutoff, PageRequest.of(0, batchSize));
        Instant now = Instant.now();
        for (Payment p : stale) {
            long ageSeconds = Duration.between(p.getCreatedAt(), now).toSeconds();
            log.warn("Reconciliation found stale {} paymentId={} paymentReference={} bookingId={} amount={} ageSeconds={}",
                    p.getStatus(), p.getId(), p.getPaymentReference(),
                    p.getBookingId(), p.getAmount(), ageSeconds);
            metrics.incStalePayment(p.getStatus().name());
        }
        if (stale.size() == batchSize) {
            log.warn("Payment staleness sweep hit batch cap ({}); more rows likely behind it.", batchSize);
        }
    }

    private void retryUnconfirmedBookings() {
        List<Payment> unconfirmed = paymentRepository.findByStatus(
                PaymentStatus.COMPLETED_UNCONFIRMED, PageRequest.of(0, batchSize));
        for (Payment p : unconfirmed) {
            // Per-row isolation: one booking's persistent rejection must not
            // stop the rest of the queue from healing.
            try {
                Map<String, Object> confirmed = bookingServiceClient.confirmBooking(p.getBookingId());
                Object confirmation = confirmed == null ? null : confirmed.get("confirmationNumber");
                paymentRecordService.resolveUnconfirmed(p.getId(),
                        confirmation == null ? null : confirmation.toString());
                metrics.incUnconfirmedRetry("resolved");
                log.info("Reconciler resolved COMPLETED_UNCONFIRMED paymentId={} paymentReference={} confirmation={}",
                        p.getId(), p.getPaymentReference(), confirmation);
            } catch (RuntimeException e) {
                metrics.incUnconfirmedRetry("still_failing");
                log.warn("Reconciler confirm retry still failing paymentId={} paymentReference={} veenguRef={} bookingId={} reason={}",
                        p.getId(), p.getPaymentReference(), p.getVeenguTransactionId(),
                        p.getBookingId(), e.getMessage());
            }
        }
    }
}
