package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.CodeStatusResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentRail;
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

/**
 * Scheduled scans over both money ledgers.
 *
 * <p><b>1. {@code transactions} (wallet transfers/withdrawals):</b> surfaces
 * rows still PENDING long after they should have flipped — the
 * Oradian-success-but-local-write-failed class of bug. Observe-only: log +
 * counter; resolution is operator-driven (see the original class notes).
 *
 * <p><b>2. {@code payment} (2D-code checkout payments — any order type):</b>
 * three sweeps.
 * <ul>
 *   <li><b>Code-status poll (every ~20s):</b> THE resolver of the 2D-code
 *       flow. Each TOKEN_ISSUED row is queried upstream by its
 *       {@code authNumber}: Paid/Claimed → confirm the order through its
 *       {@code OrderGateway} → SUCCEEDED (confirm failure →
 *       COMPLETED_UNCONFIRMED, money HAS moved);
 *       Expired/Timed Out → EXPIRED (slot freed for a fresh code); still
 *       New → expired locally only once our TTL + grace has passed.
 *       <b>UNKNOWN/error never expires a row</b> — a code the customer may
 *       have paid must keep blocking the slot until an answer or an
 *       operator; auto-expiring it would invite a second charge.</li>
 *   <li><b>Stale PENDING / IN_DOUBT (every minute):</b> PENDING rows past
 *       the threshold mean the service died between opening the row and
 *       recording the generate outcome — no code was ever DELIVERED (the
 *       send happens after TOKEN_ISSUED), so closing FAILED is safe and
 *       frees the slot. IN_DOUBT has no writer in the code flow; legacy
 *       rows are logged + counted for the operator, never auto-resolved.</li>
 *   <li><b>COMPLETED_UNCONFIRMED (self-heal):</b> the customer PAID the code
 *       but the order confirm failed. Product-side confirms are idempotent
 *       replays, so this sweep RETRIES them through the row's gateway; on
 *       success the row is promoted to SUCCEEDED. Rows that keep failing
 *       stay put and bump
 *       {@code payment.payments.unconfirmed_retry{outcome=still_failing}} —
 *       a sustained drip there is customers who paid without their
 *       tickets/goods, the loudest page this service owns (refund via the
 *       Merchant API is manual: real-time reversals are NOT available for
 *       code-based transactions, per the doc).</li>
 * </ul>
 */
@Component
@Slf4j
public class ReconciliationJob {

    /** Ticket-payment states the staleness sweep watches (non-terminal, non-code). */
    private static final EnumSet<PaymentStatus> STALE_WATCH =
            EnumSet.of(PaymentStatus.PENDING, PaymentStatus.IN_DOUBT);

    /**
     * Slack past the local code TTL before a still-New code is expired —
     * absorbs clock skew with InnBucks and a poll cycle's worth of lag.
     */
    private static final Duration CODE_EXPIRY_GRACE = Duration.ofMinutes(2);

    private final TransactionRepository repository;
    private final PaymentRepository paymentRepository;
    private final PaymentRecordService paymentRecordService;
    private final InnbucksApiClient innbucksApiClient;
    private final PaymentMetrics metrics;
    private final innbucks.paymentservice.service.CodePaymentResolutionService resolutionService;
    private final innbucks.paymentservice.service.ZimswitchCardPaymentService zimswitchCardPaymentService;
    private final UnconfirmedPaymentAlerter unconfirmedAlerter;
    private final Duration stalePendingThreshold;
    private final Duration cardPollMinInterval;
    private final int batchSize;

    public ReconciliationJob(
            TransactionRepository repository,
            PaymentRepository paymentRepository,
            PaymentRecordService paymentRecordService,
            InnbucksApiClient innbucksApiClient,
            PaymentMetrics metrics,
            innbucks.paymentservice.service.CodePaymentResolutionService resolutionService,
            innbucks.paymentservice.service.ZimswitchCardPaymentService zimswitchCardPaymentService,
            UnconfirmedPaymentAlerter unconfirmedAlerter,
            innbucks.paymentservice.client.ZimswitchProperties zimswitchProperties,
            @Value("${payment-service.reconciliation.stale-pending-threshold:PT5M}") Duration stalePendingThreshold,
            @Value("${payment-service.reconciliation.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.paymentRepository = paymentRepository;
        this.paymentRecordService = paymentRecordService;
        this.innbucksApiClient = innbucksApiClient;
        this.metrics = metrics;
        this.resolutionService = resolutionService;
        this.zimswitchCardPaymentService = zimswitchCardPaymentService;
        this.unconfirmedAlerter = unconfirmedAlerter;
        // The poller's share of the two-reads-per-checkout-per-minute budget
        // — one value with the client config so they can't drift apart.
        this.cardPollMinInterval = zimswitchProperties.getStatusPollMinInterval();
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

    /**
     * The 2D-code resolver. Tight cadence (default 20s) because this IS the
     * payment confirmation path — the customer approves the code in their
     * app and this poll is what turns that into a confirmed booking.
     */
    @Scheduled(fixedDelayString = "${payment-service.code-poll.interval:PT20S}")
    public void pollCodePayments() {
        // Rail-scoped: card rows are resolved by pollCardPayments below —
        // their open state is opaque to the InnBucks code-inquiry endpoint.
        List<Payment> open = paymentRepository.findByStatusAndPaymentRail(
                PaymentStatus.TOKEN_ISSUED, PaymentRail.INNBUCKS_CODE, PageRequest.of(0, batchSize));
        if (open.isEmpty()) {
            return;
        }
        if (!innbucksApiClient.isConfigured()) {
            log.warn("Code poll: {} TOKEN_ISSUED rows but the InnBucks API is not configured — cannot resolve",
                    open.size());
            return;
        }
        for (Payment p : open) {
            // Per-row isolation: one code's query failure must not stall the rest.
            try {
                resolveCodePayment(p);
            } catch (RuntimeException e) {
                metrics.incCodeResolution("error");
                log.warn("Code poll failed for paymentReference={} — leaving row for next pass: {}",
                        p.getPaymentReference(), e.getMessage());
            }
        }
    }

    private void resolveCodePayment(Payment p) {
        if (p.getInnbucksCode() == null || p.getInnbucksCode().isBlank()) {
            // Should be impossible (markTokenIssued always records it) — but a
            // row we cannot inquire on must never be guessed into a terminal state.
            metrics.incCodeResolution("unqueryable");
            log.error("TOKEN_ISSUED row has no innbucksCode — cannot poll paymentId={} paymentReference={}",
                    p.getId(), p.getPaymentReference());
            return;
        }
        // /api/code/inquiry is keyed by the CODE the customer pays, not the authNumber.
        CodeStatusResult result = innbucksApiClient.inquireCodeStatus(p.getInnbucksCode());
        switch (result.status()) {
            // Terminal transitions live in CodePaymentResolutionService — the
            // SAME implementation the customer-triggered instant check uses,
            // so the money rules can never drift between the two paths.
            case PAID, CLAIMED -> resolutionService.completePaid(p, result.rawStatus());
            case EXPIRED -> resolutionService.markExpiredUpstream(p, result.rawStatus());
            case TIMED_OUT -> resolutionService.markExpiredUpstream(p, "Timed Out");
            case NEW -> {
                // Still waiting on the customer. Expire only once OUR deadline
                // + grace passed — and only because upstream POSITIVELY says
                // it is still unpaid (New), so no money can be in flight.
                Instant deadline = p.getCodeExpiresAt() == null
                        ? p.getCreatedAt().plus(CODE_EXPIRY_GRACE)
                        : p.getCodeExpiresAt().plus(CODE_EXPIRY_GRACE);
                if (Instant.now().isAfter(deadline)) {
                    paymentRecordService.markExpired(p.getId(),
                            "Local TTL elapsed and InnBucks still reports New — closing unpaid");
                    metrics.incCodeResolution("expired");
                } else {
                    metrics.incCodeResolution("still_pending");
                }
            }
            case ERROR, UNKNOWN -> {
                // NEVER guess. The customer may have paid; expiring would free
                // the slot and invite a double charge. The row stays put and
                // the metric drips — sustained unknowns are an operator page.
                metrics.incCodeResolution("unknown");
                log.warn("Code status unresolvable paymentReference={} status={} raw='{}' msg='{}' — leaving row",
                        p.getPaymentReference(), result.status(), result.rawStatus(), result.responseMsg());
            }
        }
    }


    /**
     * The card-rail resolver: polls open ZimSwitch COPYandPAY checkouts.
     * Wider cadence than the code poll because the gateway throttles status
     * reads to TWO per checkout per minute — the per-row
     * {@code card_status_checked_at} gate inside
     * {@code resolveOpenCheckout} is the real budget-keeper (it also
     * arbitrates with the customer-triggered instant check), this interval
     * just sets how often the sweep offers each row a chance.
     *
     * <p>All money rules live in
     * {@link innbucks.paymentservice.service.ZimswitchCardPaymentService#resolveOpenCheckout}
     * — the SAME implementation the instant check uses: paid → confirm →
     * SUCCEEDED (echo mismatch parks IN_DOUBT; confirm failure leaves
     * COMPLETED_UNCONFIRMED for the shared retry sweep); declines keep the
     * checkout open for a shopper retry; NOT_FOUND past the checkout
     * ceiling is the positive never-paid answer that frees the slot.
     */
    @Scheduled(fixedDelayString = "${payment-service.card-poll.interval:PT30S}")
    public void pollCardPayments() {
        List<Payment> open = paymentRepository.findByStatusAndPaymentRail(
                PaymentStatus.TOKEN_ISSUED, PaymentRail.ZIMSWITCH_CARD, PageRequest.of(0, batchSize));
        if (open.isEmpty()) {
            return;
        }
        if (!zimswitchCardPaymentService.isRailConfigured()) {
            log.warn("Card poll: {} TOKEN_ISSUED card rows but ZimSwitch is not configured — cannot resolve",
                    open.size());
            return;
        }
        for (Payment p : open) {
            // Per-row isolation: one checkout's failure must not stall the rest.
            try {
                zimswitchCardPaymentService.resolveOpenCheckout(p, cardPollMinInterval);
            } catch (RuntimeException e) {
                metrics.incCardResolution("error");
                log.warn("Card poll failed for paymentReference={} — leaving row for next pass: {}",
                        p.getPaymentReference(), e.getMessage());
            }
        }
    }

    /** Code-payment ledger sweeps (stale watch + unconfirmed self-heal). */
    @Scheduled(fixedDelayString = "${payment-service.reconciliation.scan-interval:PT1M}")
    public void scanPayments() {
        sweepStalePayments();
        retryUnconfirmedOrders();
    }

    private void sweepStalePayments() {
        Instant cutoff = Instant.now().minus(stalePendingThreshold);
        List<Payment> stale = paymentRepository.findByStatusInAndCreatedAtBefore(
                STALE_WATCH, cutoff, PageRequest.of(0, batchSize));
        Instant now = Instant.now();
        for (Payment p : stale) {
            long ageSeconds = Duration.between(p.getCreatedAt(), now).toSeconds();
            log.warn("Reconciliation found stale {} paymentId={} paymentReference={} orderType={} orderRef={} amount={} ageSeconds={}",
                    p.getStatus(), p.getId(), p.getPaymentReference(),
                    p.getOrderType(), p.getOrderRef(), p.getAmount(), ageSeconds);
            metrics.incStalePayment(p.getStatus().name());
            if (p.getStatus() == PaymentStatus.PENDING) {
                // Code flow truth: a PENDING row this old means we died between
                // opening it and recording the generate outcome. Even if a code
                // was minted upstream, it was never DELIVERED (delivery happens
                // after TOKEN_ISSUED) so nobody can pay it — closing FAILED is
                // safe and frees the order's payment slot for a clean retry.
                paymentRecordService.markFailed(p.getId(), "stale_pending",
                        "No code was recorded before the staleness threshold — closing; slot freed for retry");
            }
            // IN_DOUBT: no writer in the code flow; legacy rows are operator
            // territory — observed + counted, never auto-resolved.
        }
        if (stale.size() == batchSize) {
            log.warn("Payment staleness sweep hit batch cap ({}); more rows likely behind it.", batchSize);
        }
    }

    private void retryUnconfirmedOrders() {
        List<Payment> unconfirmed = paymentRepository.findByStatus(
                PaymentStatus.COMPLETED_UNCONFIRMED, PageRequest.of(0, batchSize));
        for (Payment p : unconfirmed) {
            // Per-row isolation: one order's persistent rejection must not
            // stop the rest of the queue from healing. confirmOrder never
            // throws (unexpected errors map to UNREACHABLE) and the ledger
            // transitions are no-throw by contract; the catch is the last
            // line against a repository/DB blip mid-row.
            try {
                ConfirmOutcome outcome = resolutionService.confirmOrder(p);
                if (outcome.succeeded()) {
                    paymentRecordService.resolveUnconfirmed(p.getId(), outcome.confirmationNumber());
                    metrics.incUnconfirmedRetry("resolved");
                    log.info("Reconciler resolved COMPLETED_UNCONFIRMED paymentId={} paymentReference={} confirmation={}",
                            p.getId(), p.getPaymentReference(), outcome.confirmationNumber());
                } else {
                    metrics.incUnconfirmedRetry("still_failing");
                    log.warn("Reconciler confirm retry still failing paymentId={} paymentReference={} upstreamRef={} orderType={} orderRef={} reason={}",
                            p.getId(), p.getPaymentReference(), p.getVeenguTransactionId(),
                            p.getOrderType(), p.getOrderRef(), outcome.reason());
                    // One-time human escalation: operator email + customer
                    // reassurance on the FIRST still-failing retry per payment
                    // (operator_alerted_at guards re-alerting). Best-effort.
                    unconfirmedAlerter.onStillFailing(p, outcome.reason());
                }
            } catch (RuntimeException e) {
                metrics.incUnconfirmedRetry("still_failing");
                log.warn("Reconciler confirm retry errored paymentId={} paymentReference={} reason={}",
                        p.getId(), p.getPaymentReference(), e.getMessage());
                unconfirmedAlerter.onStillFailing(p, e.getMessage());
            }
        }
    }
}
