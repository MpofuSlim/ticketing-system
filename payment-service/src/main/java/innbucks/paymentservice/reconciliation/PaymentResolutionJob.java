package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.CodeStatusResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.CodePaymentResolutionService;
import innbucks.paymentservice.service.PaymentRecordService;
import innbucks.paymentservice.service.ZimswitchCardPaymentService;
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
 * <b>THIS CLASS IS THE PAYMENT CONFIRMATION PATH. Do not delete it, and do not
 * let it stop being a {@code @Component}.</b>
 *
 * <p>A customer approving an InnBucks code (or completing a ZimSwitch checkout)
 * produces NO callback to us — there are no webhooks on either rail. The only
 * thing that turns "the customer paid" into "the booking is confirmed" is these
 * scheduled sweeps asking the gateway. With them absent the money still leaves
 * the customer's wallet, the row stays {@link PaymentStatus#TOKEN_ISSUED}
 * forever, the order never confirms, and its payment slot stays held — silently,
 * with no error anywhere, because nothing is running to fail.
 *
 * <p>That is not hypothetical: these four sweeps previously lived in a class
 * called {@code ReconciliationJob}, which also carried the (since-retired)
 * Oradian wallet-transfer scan. Removing Oradian removed the file, and with it
 * every ticket payment's resolver. The rails' config keys, repository sweeps and
 * {@link CodePaymentResolutionService} all survived — only the scheduler driving
 * them went — so nothing failed loudly; payments merely stopped completing. They
 * live in their own class now, named for what they do, so the next removal of an
 * unrelated integration cannot take them along.
 *
 * <p>The sweeps:
 * <ul>
 *   <li><b>Code-status poll (~20s):</b> THE resolver of the 2D-code flow. Each
 *       TOKEN_ISSUED row is queried upstream: Paid/Claimed → confirm the order
 *       through its {@code OrderGateway} → SUCCEEDED (confirm failure →
 *       COMPLETED_UNCONFIRMED, money HAS moved); Expired/Timed Out → EXPIRED
 *       (slot freed for a fresh code); still New → expired locally only once our
 *       TTL + grace has passed. <b>UNKNOWN/error never expires a row</b> — a code
 *       the customer may have paid must keep blocking the slot until an answer or
 *       an operator; auto-expiring it would invite a second charge.</li>
 *   <li><b>Card-status poll (~30s):</b> the same job for open ZimSwitch
 *       COPYandPAY checkouts.</li>
 *   <li><b>EcoCash Query poll (~20s):</b> the same job for open EcoCash EIP
 *       charges — the customer approves a PIN prompt on their phone and this
 *       poll (plus the notify-webhook trigger, which runs the SAME resolver)
 *       turns that into a confirmed order. The notify webhook is a fast-path
 *       trigger only; this sweep is the authority.</li>
 *   <li><b>Stale PENDING / IN_DOUBT (every minute):</b> PENDING rows past the
 *       threshold mean the service died between opening the row and recording the
 *       generate outcome — no code was ever DELIVERED (the send happens after
 *       TOKEN_ISSUED), so closing FAILED is safe and frees the slot. IN_DOUBT has
 *       no writer in the code flow; legacy rows are logged + counted for the
 *       operator, never auto-resolved.</li>
 *   <li><b>COMPLETED_UNCONFIRMED (self-heal, every minute):</b> the customer PAID
 *       but the order confirm failed. Product-side confirms are idempotent
 *       replays, so this RETRIES them; on success the row is promoted to
 *       SUCCEEDED. Rows that keep failing stay put and bump
 *       {@code payment.payments.unconfirmed_retry{outcome=still_failing}} — a
 *       sustained drip there is customers who paid without their tickets, the
 *       loudest page this service owns (refund via the Merchant API is manual:
 *       real-time reversals are NOT available for code-based transactions).</li>
 * </ul>
 */
@Component
@Slf4j
public class PaymentResolutionJob {

    /** Ticket-payment states the staleness sweep watches (non-terminal, non-code). */
    private static final EnumSet<PaymentStatus> STALE_WATCH =
            EnumSet.of(PaymentStatus.PENDING, PaymentStatus.IN_DOUBT);

    /**
     * Slack past the local code TTL before a still-New code is expired —
     * absorbs clock skew with InnBucks and a poll cycle's worth of lag.
     */
    private static final Duration CODE_EXPIRY_GRACE = Duration.ofMinutes(2);

    private final PaymentRepository paymentRepository;
    private final PaymentRecordService paymentRecordService;
    private final InnbucksApiClient innbucksApiClient;
    private final PaymentMetrics metrics;
    private final CodePaymentResolutionService resolutionService;
    private final ZimswitchCardPaymentService zimswitchCardPaymentService;
    private final innbucks.paymentservice.service.EcocashPaymentService ecocashPaymentService;
    private final UnconfirmedPaymentAlerter unconfirmedAlerter;
    private final Duration stalePendingThreshold;
    private final Duration cardPollMinInterval;
    private final int batchSize;

    public PaymentResolutionJob(
            PaymentRepository paymentRepository,
            PaymentRecordService paymentRecordService,
            InnbucksApiClient innbucksApiClient,
            PaymentMetrics metrics,
            CodePaymentResolutionService resolutionService,
            ZimswitchCardPaymentService zimswitchCardPaymentService,
            innbucks.paymentservice.service.EcocashPaymentService ecocashPaymentService,
            UnconfirmedPaymentAlerter unconfirmedAlerter,
            ZimswitchProperties zimswitchProperties,
            @Value("${payment-service.reconciliation.stale-pending-threshold:PT5M}") Duration stalePendingThreshold,
            @Value("${payment-service.reconciliation.batch-size:100}") int batchSize) {
        this.paymentRepository = paymentRepository;
        this.paymentRecordService = paymentRecordService;
        this.innbucksApiClient = innbucksApiClient;
        this.metrics = metrics;
        this.resolutionService = resolutionService;
        this.zimswitchCardPaymentService = zimswitchCardPaymentService;
        this.ecocashPaymentService = ecocashPaymentService;
        this.unconfirmedAlerter = unconfirmedAlerter;
        // The poller's share of the two-reads-per-checkout-per-minute budget
        // — one value with the client config so they can't drift apart.
        this.cardPollMinInterval = zimswitchProperties.getStatusPollMinInterval();
        this.stalePendingThreshold = stalePendingThreshold;
        this.batchSize = batchSize;
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
     * {@link ZimswitchCardPaymentService#resolveOpenCheckout} — the SAME
     * implementation the instant check uses: paid → confirm → SUCCEEDED (echo
     * mismatch parks IN_DOUBT; confirm failure leaves COMPLETED_UNCONFIRMED for
     * the shared retry sweep); declines keep the checkout open for a shopper
     * retry; NOT_FOUND past the checkout ceiling is the positive never-paid
     * answer that frees the slot.
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

    /**
     * The EcoCash EIP resolver: polls open wallet charges via the Query
     * endpoint. Same cadence class as the code poll — this IS the rail's
     * confirmation path (the notify webhook is only a fast-path trigger into
     * the same resolver). All money rules live in
     * {@link innbucks.paymentservice.service.EcocashPaymentService#resolveOpenCharge}:
     * COMPLETED → echo-check → confirm → SUCCEEDED (confirm failure leaves
     * COMPLETED_UNCONFIRMED for the shared retry sweep); FAILED = subscriber
     * rejected = terminal FAILED, slot freed; still-pending or NOT_FOUND past
     * the prompt deadline + grace expires locally; UNKNOWN never closes a
     * row.
     */
    @Scheduled(fixedDelayString = "${payment-service.ecocash-poll.interval:PT20S}")
    public void pollEcocashPayments() {
        List<Payment> open = paymentRepository.findByStatusAndPaymentRail(
                PaymentStatus.TOKEN_ISSUED, PaymentRail.ECOCASH, PageRequest.of(0, batchSize));
        if (open.isEmpty()) {
            return;
        }
        if (!ecocashPaymentService.isRailConfigured()) {
            log.warn("EcoCash poll: {} TOKEN_ISSUED rows but the EcoCash API is not configured — cannot resolve",
                    open.size());
            return;
        }
        for (Payment p : open) {
            // Per-row isolation: one charge's query failure must not stall the rest.
            try {
                ecocashPaymentService.resolveOpenCharge(p);
            } catch (RuntimeException e) {
                metrics.incEcocashResolution("error");
                log.warn("EcoCash poll failed for paymentReference={} — leaving row for next pass: {}",
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
                // ECOCASH rows keep this guarantee by ordering, not delivery:
                // that rail transitions to TOKEN_ISSUED BEFORE its upstream
                // call (a crashed charge can leave a LIVE prompt on the
                // customer's phone), so an ECOCASH row still PENDING provably
                // never called upstream — closing it is equally safe.
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
