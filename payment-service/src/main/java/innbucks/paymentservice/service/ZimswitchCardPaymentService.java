package innbucks.paymentservice.service;

import innbucks.paymentservice.client.CardPaymentStatus;
import innbucks.paymentservice.client.CheckoutPreparation;
import innbucks.paymentservice.client.ZimswitchApiException;
import innbucks.paymentservice.client.ZimswitchApiTransientException;
import innbucks.paymentservice.client.ZimswitchCopyPayClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates a <b>ZimSwitch COPYandPAY card</b> checkout payment — the
 * card rail beside the InnBucks 2D-code rail, serving any product wired
 * behind an {@link OrderGateway}. Spec + safety analysis:
 * {@code docs/api/zimswitch-copyandpay.md}.
 *
 * <pre>
 *   1. gateway.fetch(orderRef): amount in cents, currency, payable flag
 *   2. gateway.extendHold(orderRef, now + checkout TTL + grace): the product
 *      hold provably outlives the checkout (the gateway's 30-minute ceiling)
 *      BEFORE any ledger write or upstream call
 *   3. Open a PENDING payment ledger row (REQUIRES_NEW commit)
 *   4. POST /v1/checkouts (amount in MAJOR units — the client converts;
 *      merchantTransactionId = our paymentReference; integrity=true)
 *      - created   -> TOKEN_ISSUED (checkoutId + SRI digest + local expiry)
 *      - refused   -> FAILED (no money moves on prepare), FE sees the reason
 *      - transient -> FAILED + 503 (safe: an orphaned upstream checkout is
 *        unreachable and expires within 30 min; the slot frees for a retry)
 *   5. Return PROCESSING with the widget artifacts (script URL + integrity +
 *      brands + shopperResultUrl) — the FE renders the PCI-scoped COPYandPAY
 *      widget; card data goes browser -> gateway, never through us.
 *   6. The row is resolved via GET /v1/checkouts/{id}/payment, but ONLY when
 *      the shopper comes back from ZimSwitch (the result page re-POSTs with
 *      the redirect's resourcePath) or once the checkout is past its
 *      30-minute ceiling (the poller, or any re-POST). See {@link ReadTrigger}.
 * </pre>
 *
 * <p><b>Resolution rules this rail adds over the code rail</b> (each pinned
 * in the spec doc):
 * <ul>
 *   <li><b>One-shot status read:</b> reading a FINAL outcome kills the
 *       checkoutId upstream. So on a paid read the money fact is persisted
 *       FIRST ({@code COMPLETED_UNCONFIRMED}) and the order confirm runs
 *       after — the inverse of the code rail's confirm-then-mark, because
 *       here a crash between read and write loses the outcome forever.</li>
 *   <li><b>Echo verification (the 100x guard):</b> a paid read whose
 *       amount/currency/merchantTransactionId echo doesn't match the ledger
 *       parks IN_DOUBT for an operator — never confirmed, never guessed.</li>
 *   <li><b>No status read before the shopper returns:</b> a read of a
 *       checkout nobody has paid yet ({@code 200.300.404}) kills it upstream
 *       within minutes (measured on staging, 2026-11-12: a checkout never read
 *       still rendered at six minutes; every one we had read was "invalid"
 *       within three to five). So before the ceiling only
 *       {@link ReadTrigger#SHOPPER_RETURN} reads; the poller and a plain
 *       re-POST wait for the ceiling, when the checkout can no longer be paid
 *       and a read can no longer harm it.</li>
 *   <li><b>Declines before the ceiling don't close the row:</b> the decline is
 *       journalled and the row stays open, per the documented
 *       multi-transaction reuse. Whether the checkout survives the read that
 *       reported the decline is an open question for ZimSwitch. A decline read
 *       past the ceiling closes the row FAILED: no retry can follow it.</li>
 *   <li><b>Every read is journalled</b> with ZimSwitch's result code and what
 *       triggered it, so a checkout that dies early can be traced to the read
 *       that preceded it.</li>
 *   <li><b>Throttle:</b> two status reads per checkout per minute, enforced
 *       through the persisted {@code card_status_checked_at} stamp shared by
 *       every caller.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZimswitchCardPaymentService {

    /**
     * Slack past the local checkout TTL before a NOT_FOUND row is expired —
     * with the 28-minute local TTL this lands past the gateway's own
     * 30-minute ceiling, after which no new transaction can exist on the
     * checkout, making "no payment found" a POSITIVE never-paid answer. So
     * the checkout's last payable instant is TTL + this grace, and that is
     * what the product hold is sized from.
     */
    static final Duration CHECKOUT_EXPIRY_GRACE = Duration.ofMinutes(2);

    /** The gateway's own checkout life, for a row missing its local deadline. */
    private static final Duration GATEWAY_CHECKOUT_CEILING = Duration.ofMinutes(30);

    /** The path ZimSwitch puts in the shopper's redirect: {@code /v1/checkouts/{id}/payment}. */
    private static final String RESOURCE_PATH_PREFIX = "/v1/checkouts/";
    private static final String RESOURCE_PATH_SUFFIX = "/payment";

    private final PaymentRecordService paymentRecordService;
    private final ZimswitchCopyPayClient zimswitchClient;
    private final ZimswitchProperties zimswitchProperties;
    private final OrderGatewayRegistry orderGatewayRegistry;
    private final CodePaymentResolutionService resolutionService;
    private final PaymentMetrics metrics;

    /** Cell currency fallback, mirroring the code rail. */
    @Value("${innbucks.currency:USD}")
    private String cellCurrency;

    /** Whether the card rail can talk to its gateway (config present). */
    public boolean isRailConfigured() {
        return zimswitchClient.isConfigured();
    }

    /** Everything the FE needs to render the COPYandPAY widget (or a terminal verdict). */
    @Builder
    public record CardCheckoutOutcome(
            Payment payment,
            boolean failed,
            String checkoutId,
            String widgetScriptUrl,
            String checkoutIntegrity,
            String brands,
            String shopperResultUrl,
            Instant checkoutExpiresAt,
            String upstreamCode,
            String upstreamMessage) {
    }

    /**
     * Start a card checkout for any product order. Mirrors
     * {@link InnbucksPaymentService#processPayment} step for step; throws the
     * same {@link InvalidPaymentRequestException} vocabulary so the
     * controller's error mapping is rail-agnostic.
     */
    public CardCheckoutOutcome startCheckout(OrderType orderType, String orderRef) {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(orderRef, "orderRef");
        OrderGateway gateway = orderGatewayRegistry.forType(orderType);
        String noun = orderType == OrderType.BOOKING ? "booking" : "order";

        // Fail BEFORE claiming the order's payment slot when the rail can't
        // work at all — an unconfigured cell must refuse cleanly, not burn
        // the slot on a row that can only fail.
        //
        // canStartCheckout(), not isConfigured(): credentials alone are not
        // enough. Without a usable shopperResultUrl the FE has no form action
        // and cannot render a payment form, yet the checkout we would mint is
        // real and its ledger row holds the order's ONLY payment slot (across
        // both rails) for ~28 minutes — locking the customer out of the
        // InnBucks code rail too. The two are split so the reconciler can keep
        // resolving already-open checkouts on isConfigured() alone.
        if (!zimswitchClient.canStartCheckout()) {
            // Distinct tag: "credentials missing" and "result URL missing" are
            // different operator fixes, and the second is invisible from the
            // outside (the rail looks provisioned).
            metrics.incCardCheckout(zimswitchClient.isConfigured()
                    ? "no_shopper_result_url" : "unconfigured");
            if (zimswitchClient.isConfigured()) {
                log.error("[zimswitch-card] card checkout refused: ZIMSWITCH_SHOPPER_RESULT_URL is blank or "
                        + "not an absolute http(s) URL. Credentials ARE present, so the rail looks provisioned "
                        + "but no shopper can complete a payment. Set it to the FE's card-result page.");
            }
            throw new InvalidPaymentRequestException(
                    "Card payments are not available on this deployment", 503);
        }

        // One active payment per order — across BOTH rails (the
        // uq_payment_active_order index is rail-agnostic): an open InnBucks
        // code blocks a card attempt for the same order until it lapses,
        // and vice versa. The controller's replay path returns the existing
        // attempt's artifacts instead of ever reaching here.
        if (paymentRecordService.hasActiveOrSucceededPayment(orderType, orderRef)) {
            throw new InvalidPaymentRequestException(
                    "A payment for this " + noun + " is already in progress or completed", 409);
        }

        OrderSnapshot snapshot = gateway.fetch(orderRef);
        if (!snapshot.payable()) {
            throw new InvalidPaymentRequestException(
                    "This " + noun + " is not awaiting payment — it may already be paid, cancelled or expired",
                    409);
        }
        long amountCents = snapshot.amountCents();
        if (amountCents <= 0) {
            throw new InvalidPaymentRequestException(
                    capitalize(noun) + " has no positive amount; cannot request payment", 422);
        }
        // The rail settles in the cell currency only — refuse anything else
        // BEFORE the hold, the ledger row and the wire (see SettlementCurrency).
        String currency = SettlementCurrency.requireCellCurrency(snapshot.currency(), cellCurrency, "Card");
        String customerMsisdn = snapshot.payerMsisdn();
        if (customerMsisdn == null || customerMsisdn.isBlank()) {
            throw new InvalidPaymentRequestException(
                    capitalize(noun) + " has no payer phone number — create the " + noun
                            + " with a phone number before paying", 400);
        }

        // Product hold must outlive the checkout BEFORE any ledger write or
        // upstream call — a refusal means the order already lapsed, with
        // ZERO money moved. A checkout stays payable until the gateway's
        // 30-minute ceiling (local TTL + grace), so the hold is sized from
        // that: a code-sized hold left a card paid after minute 13 with no
        // ticket.
        gateway.extendHold(orderRef, Instant.now()
                .plus(zimswitchProperties.getCheckoutTtl()).plus(CHECKOUT_EXPIRY_GRACE));

        String paymentReference = SettlementReference.forTag(snapshot.settlementTag());
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .paymentRail(PaymentRail.ZIMSWITCH_CARD)
                .orderType(orderType)
                .orderRef(orderRef)
                .bookingId(orderType == OrderType.BOOKING ? asUuid(orderRef) : null)
                .customerMsisdn(customerMsisdn)
                .amount(BigDecimal.valueOf(amountCents, 2))
                .currency(currency)
                .build();
        Payment opened;
        try {
            opened = paymentRecordService.openPending(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (!PaymentRecordService.isActiveOrderConflict(e)) {
                // A schema refusal, not a race: surface it as the defect it is.
                log.error("[zimswitch-card] ledger refused the payment row (not the active-order index) "
                        + "orderType={} orderRef={}", orderType, orderRef, e);
                throw e;
            }
            log.warn("[zimswitch-card] concurrent payment refused by active-order index orderType={} orderRef={}",
                    orderType, orderRef);
            throw new InvalidPaymentRequestException(
                    "A payment for this " + noun + " is already in progress or completed", 409);
        }

        CheckoutPreparation prepared;
        try {
            prepared = zimswitchClient.prepareCheckout(paymentReference, amountCents, currency);
        } catch (ZimswitchApiTransientException e) {
            // Prepare moves NO money. Worst case a checkout exists upstream
            // that nobody will render — it expires within 30 minutes. Closing
            // FAILED frees the slot for a clean retry; deliberately never
            // retried here (a retry could mint a second live checkout).
            log.warn("[zimswitch-card] checkout prepare transient paymentReference={} status={}",
                    paymentReference, e.getStatusCode());
            paymentRecordService.markFailed(opened.getId(), "zimswitch_unreachable",
                    "Checkout preparation transient failure: " + e.getMessage());
            metrics.incCardCheckout("prepare_transient");
            throw new InvalidPaymentRequestException(
                    "Card payments are temporarily unavailable; please try again shortly", 503);
        } catch (ZimswitchApiException e) {
            log.error("[zimswitch-card] checkout prepare rejected paymentReference={} status={}",
                    paymentReference, e.getStatusCode(), e);
            paymentRecordService.markFailed(opened.getId(), "zimswitch_rejected", e.getMessage());
            metrics.incCardCheckout("prepare_rejected");
            throw new InvalidPaymentRequestException(
                    "Payment request was rejected by the card gateway", 500);
        }

        if (!prepared.created()) {
            paymentRecordService.markFailed(opened.getId(),
                    prepared.resultCode() != null ? prepared.resultCode() : "checkout_refused",
                    prepared.resultMessage());
            metrics.incCardCheckout("prepare_refused");
            return CardCheckoutOutcome.builder()
                    .payment(opened)
                    .failed(true)
                    .upstreamCode(prepared.resultCode())
                    .upstreamMessage(prepared.resultMessage() != null ? prepared.resultMessage()
                            : "The card gateway could not start this payment; please try again")
                    .build();
        }

        // Stamped AFTER the checkout exists, deliberately not reused from the
        // hold above: the NOT_FOUND rule closes the row at this deadline +
        // CHECKOUT_EXPIRY_GRACE, which must land at or past the gateway's
        // ceiling, and the ceiling runs from the checkout's creation. The hold
        // still covers it: the gap is the hold call + ledger open + prepare
        // round trip, bounded by their timeouts at seconds, well inside
        // HOLD_SAFETY_MARGIN.
        Instant expiresAt = Instant.now().plus(zimswitchProperties.getCheckoutTtl());
        paymentRecordService.markCardCheckoutIssued(opened.getId(),
                prepared.checkoutId(), prepared.integrity(), expiresAt);
        metrics.incCardCheckout("prepared");

        log.info("[zimswitch-card] checkout prepared paymentReference={} orderType={} checkoutId={} ndc={} expiresAt={}",
                paymentReference, orderType, prepared.checkoutId(), prepared.ndc(), expiresAt);
        return widgetOutcome(opened, prepared.checkoutId(), prepared.integrity(), expiresAt);
    }

    /**
     * Re-surface the widget artifacts for an existing open checkout (the
     * replay path). Only call for a TOKEN_ISSUED card row whose deadline has
     * not passed — the documented checkout-reuse model: reload/back-button/
     * declined-retry all re-render the SAME checkout.
     */
    public CardCheckoutOutcome replayOpenCheckout(Payment p) {
        // Same rule as startCheckout, enforced on the OTHER path that emits
        // widget artifacts. Without it the guard was half a guard: a row
        // minted before the cell was fully provisioned (or by an old pod
        // mid-rolling-deploy) would replay a full artifact set with an empty
        // form action — precisely the unrenderable response the start gate
        // exists to prevent. Returning null makes the caller render the
        // honest "being processed" state instead of a dead payment form.
        if (!zimswitchClient.canStartCheckout()) {
            log.error("[zimswitch-card] cannot re-surface widget artifacts for paymentReference={} — "
                            + "ZIMSWITCH_SHOPPER_RESULT_URL is blank or not an absolute http(s) URL. "
                            + "This row holds the order's payment slot until it lapses.",
                    p.getPaymentReference());
            metrics.incCardResolution("replay_unrenderable");
            return null;
        }
        return widgetOutcome(p, p.getCheckoutId(), p.getCheckoutIntegrity(), p.getCodeExpiresAt());
    }

    private CardCheckoutOutcome widgetOutcome(Payment p, String checkoutId,
                                              String integrity, Instant expiresAt) {
        return CardCheckoutOutcome.builder()
                .payment(p)
                .failed(false)
                .checkoutId(checkoutId)
                .widgetScriptUrl(zimswitchClient.widgetScriptUrl(checkoutId))
                .checkoutIntegrity(integrity)
                .brands(zimswitchProperties.getBrands())
                .shopperResultUrl(zimswitchProperties.getShopperResultUrl())
                .checkoutExpiresAt(expiresAt)
                .build();
    }

    /**
     * Outcome of a status read (or a skipped one) on an open card checkout.
     * {@code EXPIRED} means the row just closed unpaid and its slot is free:
     * EXPIRED (no payment past the ceiling) or FAILED (declined past it).
     */
    public enum CardCheckOutcome { PAID, EXPIRED, PENDING }

    /**
     * Why a caller wants the checkout's status read. It decides whether a read
     * may happen before the checkout's ceiling.
     *
     * <p>A read of a checkout nobody has paid yet is not harmless: on staging
     * (2026-11-12) a checkout we never read still rendered its card form six
     * minutes after it was made, and every checkout we had read answered
     * "checkoutId is invalid" within three to five minutes. Reading while the
     * shopper was still typing their card killed the form under them. So
     * before the ceiling only the shopper's return reads.
     */
    public enum ReadTrigger {
        /**
         * The shopper came back from ZimSwitch to {@code shopperResultUrl}
         * and the result page re-POSTed with the redirect's
         * {@code resourcePath}. The one moment a read before the ceiling is
         * wanted: the shopper has finished with the form.
         */
        SHOPPER_RETURN("on the shopper's return"),
        /** A plain re-POST (reload, retry). Reads only past the ceiling. */
        CUSTOMER_RETRY("on a customer re-POST"),
        /** The reconciler's card sweep. Reads only past the ceiling. */
        POLLER("by the card poller");

        private final String journalLabel;

        ReadTrigger(String journalLabel) {
            this.journalLabel = journalLabel;
        }
    }

    /**
     * The instant after which the checkout can take no payment: the local
     * deadline plus {@link #CHECKOUT_EXPIRY_GRACE}, which lands past the
     * gateway's own 30-minute ceiling. A row missing its local deadline
     * (defensive; {@code markCardCheckoutIssued} always stamps one) falls back
     * to the gateway ceiling from creation, never earlier.
     */
    static Instant ceiling(Payment p) {
        Instant deadline = p.getCodeExpiresAt() != null
                ? p.getCodeExpiresAt()
                : p.getCreatedAt().plus(GATEWAY_CHECKOUT_CEILING).minus(CHECKOUT_EXPIRY_GRACE);
        return deadline.plus(CHECKOUT_EXPIRY_GRACE);
    }

    static boolean isPastCeiling(Payment p, Instant now) {
        return now.isAfter(ceiling(p));
    }

    /**
     * The sweep's cut-offs for {@code PaymentRepository.findOpenPastCeiling}:
     * a row is past its {@link #ceiling} when its local deadline is before
     * {@code deadlineBefore}, or, with no deadline, when it was created before
     * {@code createdBefore}. Kept here so the query and {@link #ceiling} share
     * one definition.
     */
    public record CeilingCutoffs(Instant deadlineBefore, Instant createdBefore) {
        public static CeilingCutoffs at(Instant now) {
            return new CeilingCutoffs(now.minus(CHECKOUT_EXPIRY_GRACE), now.minus(GATEWAY_CHECKOUT_CEILING));
        }
    }

    /**
     * Whether a POST's {@code resourcePath} is the redirect ZimSwitch sends
     * for THIS row's checkout. Compared, never used: the status URL is always
     * rebuilt from the stored checkout id (the client's SSRF rule). A value
     * naming another checkout, or none, is a plain re-POST.
     */
    public static boolean isShopperReturn(String resourcePath, Payment p) {
        if (resourcePath == null || p == null || p.getCheckoutId() == null || p.getCheckoutId().isBlank()) {
            return false;
        }
        return resourcePath.strip().equals(RESOURCE_PATH_PREFIX + p.getCheckoutId() + RESOURCE_PATH_SUFFIX);
    }

    /**
     * Resolve an open card checkout by querying the gateway — THE resolver
     * for this rail, shared by the shopper's return, a re-POST and the
     * reconciler poll so the money rules cannot drift between them.
     *
     * <p>Before the checkout's {@link #ceiling} only
     * {@link ReadTrigger#SHOPPER_RETURN} reads; any other trigger reports
     * PENDING without calling the gateway.
     *
     * @param minGap  minimum age of the previous status read before another
     *                is allowed (the two-per-minute throttle share for this
     *                caller); a fresher stamp skips the read and reports
     *                PENDING
     * @param trigger why the read is wanted, which decides whether it may
     *                happen before the ceiling, and is journalled with it
     */
    public CardCheckOutcome resolveOpenCheckout(Payment p, Duration minGap, ReadTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED
                || p.getCheckoutId() == null || p.getCheckoutId().isBlank()) {
            return CardCheckOutcome.PENDING;
        }
        Instant now = Instant.now();
        boolean pastCeiling = isPastCeiling(p, now);
        if (!pastCeiling && trigger != ReadTrigger.SHOPPER_RETURN) {
            // The shopper may still be on the card form: a read now would
            // kill the checkout under them. Wait for their return or the
            // ceiling.
            metrics.incCardResolution("read_deferred");
            return CardCheckOutcome.PENDING;
        }
        Instant lastChecked = p.getCardStatusCheckedAt();
        if (lastChecked != null && lastChecked.isAfter(now.minus(minGap))) {
            metrics.incCardResolution("throttled_skip");
            return CardCheckOutcome.PENDING;
        }
        // Stamp BEFORE the read: even a crash mid-read counts against the
        // throttle, so a crash-loop can never hammer the gateway.
        paymentRecordService.stampCardStatusChecked(p.getId());

        CardPaymentStatus status;
        try {
            status = zimswitchClient.getPaymentStatus(p.getCheckoutId());
        } catch (RuntimeException e) {
            metrics.incCardResolution("error");
            log.warn("[zimswitch-card] status read unavailable paymentReference={} trigger={} cause={}",
                    p.getPaymentReference(), trigger, e.getMessage());
            // The request may still have reached ZimSwitch, so it is on the
            // record like any other read.
            paymentRecordService.noteEvent(p.getId(),
                    "Card status read " + trigger.journalLabel + " got no answer: " + e.getMessage());
            return CardCheckOutcome.PENDING;
        }
        String answer = describe(status);
        log.info("[zimswitch-card] status read paymentReference={} trigger={} resultCode={} pastCeiling={}",
                p.getPaymentReference(), trigger, status.resultCode(), pastCeiling);

        switch (status.outcome()) {
            case SUCCESS, SUCCESS_MANUAL_REVIEW -> {
                // No separate journal row before the money fact: the paid
                // path's own transition carries the result code.
                return onPaidRead(p, status, trigger);
            }
            case REJECTED -> {
                if (pastCeiling) {
                    // No retry can follow a decline once the checkout is
                    // past its ceiling: close the attempt and free the slot.
                    paymentRecordService.markFailed(p.getId(),
                            status.resultCode() != null ? status.resultCode() : "card_declined",
                            "Card declined, read " + trigger.journalLabel + " past the checkout ceiling: "
                                    + answer);
                    metrics.incCardResolution("declined_closed");
                    log.info("[zimswitch-card] attempt declined past the ceiling paymentReference={} resultCode={} — closed FAILED",
                            p.getPaymentReference(), status.resultCode());
                    return CardCheckOutcome.EXPIRED;
                }
                // Before the ceiling a decline closes the ATTEMPT, not the
                // row: the documented multi-transaction reuse lets the shopper
                // try another card. Whether the checkout survives this read is
                // an open question for ZimSwitch (docs/api/zimswitch-copyandpay.md).
                metrics.incCardResolution("declined_noted");
                paymentRecordService.noteEvent(p.getId(),
                        "Card status read " + trigger.journalLabel + ": declined " + answer
                                + " — checkout stays open for a retry");
                log.info("[zimswitch-card] attempt declined paymentReference={} resultCode={} — checkout stays open",
                        p.getPaymentReference(), status.resultCode());
                return CardCheckOutcome.PENDING;
            }
            case PENDING -> {
                metrics.incCardResolution("still_pending");
                paymentRecordService.noteEvent(p.getId(),
                        "Card status read " + trigger.journalLabel + ": still pending " + answer);
                return CardCheckOutcome.PENDING;
            }
            case CHECKOUT_NOT_FOUND -> {
                // Past the ceiling it is a POSITIVE never-paid answer: no
                // transaction exists and none can be created any more, so the
                // slot is freed. Before it (the shopper's return only) the
                // shopper came back without paying, or the payment has not
                // landed yet: keep the row.
                if (pastCeiling) {
                    paymentRecordService.markExpired(p.getId(),
                            "Checkout ceiling passed and the gateway reports no payment (read "
                                    + trigger.journalLabel + ": " + answer + ") — closing unpaid");
                    metrics.incCardResolution("expired");
                    return CardCheckOutcome.EXPIRED;
                }
                metrics.incCardResolution("not_found_pending");
                paymentRecordService.noteEvent(p.getId(),
                        "Card status read " + trigger.journalLabel + ": no payment yet " + answer);
                return CardCheckOutcome.PENDING;
            }
        }
        return CardCheckOutcome.PENDING;
    }

    /** ZimSwitch's answer as one journal fragment: {@code 200.300.404 (description)}. */
    private static String describe(CardPaymentStatus status) {
        String code = status.resultCode() != null ? status.resultCode() : "no result code";
        return status.resultMessage() != null ? code + " (" + status.resultMessage() + ")" : code;
    }

    /**
     * A paid status read — the one-shot moment. Order of operations is
     * deliberate and load-bearing:
     * <ol>
     *   <li>Echo verification first: a mismatched amount/currency/reference
     *       parks IN_DOUBT (operator) — the order is NEVER confirmed against
     *       a disputed charge.</li>
     *   <li>Persist the money fact (COMPLETED_UNCONFIRMED) IMMEDIATELY —
     *       the read cannot be repeated, so the write must be as close to it
     *       as the code can get.</li>
     *   <li>Only then confirm the order; failure leaves the row for the
     *       existing confirm-retry sweep, success promotes to SUCCEEDED.</li>
     * </ol>
     */
    private CardCheckOutcome onPaidRead(Payment p, CardPaymentStatus status, ReadTrigger trigger) {
        String gatewayTxnId = status.transactionId() != null ? status.transactionId() : p.getCheckoutId();

        String mismatch = echoMismatch(p, status);
        if (mismatch != null) {
            metrics.incCardResolution("echo_mismatch");
            log.error("[zimswitch-card] PAID READ WITH ECHO MISMATCH — parking IN_DOUBT for operator "
                            + "paymentReference={} checkoutId={} gatewayTxnId={} {}",
                    p.getPaymentReference(), p.getCheckoutId(), gatewayTxnId, mismatch);
            paymentRecordService.markInDoubt(p.getId(),
                    "Gateway reports PAID but the echo does not match the ledger (" + mismatch
                            + ") — gateway txn " + gatewayTxnId + ", result " + status.resultCode()
                            + ". Operator must reconcile against ZimSwitch records before any confirm/refund.");
            return CardCheckOutcome.PENDING;
        }

        paymentRecordService.recordCardBrand(p.getId(), status.brand());
        if (status.outcome() == innbucks.paymentservice.client.ZimswitchResultCode.SUCCESS_MANUAL_REVIEW) {
            paymentRecordService.noteEvent(p.getId(),
                    "Gateway flagged this success for manual fraud review (result " + status.resultCode() + ")");
        }

        // Money fact FIRST — the one-shot write.
        paymentRecordService.markCompletedUnconfirmed(p.getId(), gatewayTxnId,
                "Card payment succeeded (result " + status.resultCode() + ", read "
                        + trigger.journalLabel + "); confirming order");

        ConfirmOutcome outcome = resolutionService.confirmOrder(p);
        if (outcome.succeeded()) {
            paymentRecordService.resolveUnconfirmed(p.getId(), outcome.confirmationNumber());
            metrics.incCardResolution("paid");
            log.info("[zimswitch-card] paid + order confirmed paymentReference={} gatewayTxnId={} confirmation={} brand={}",
                    p.getPaymentReference(), gatewayTxnId, outcome.confirmationNumber(), status.brand());
        } else {
            metrics.incCardResolution("paid_unconfirmed");
            log.error("CARD PAID BUT ORDER CONFIRM FAILED — confirm-retry will keep trying "
                            + "paymentReference={} gatewayTxnId={} reason={}",
                    p.getPaymentReference(), gatewayTxnId, outcome.reason());
        }
        return CardCheckOutcome.PAID;
    }

    /**
     * The doc-recommended echo verification: amount, currency and
     * merchantTransactionId must match what we sent (brand/type are
     * informational). Null echoes pass — absent fields are not evidence of a
     * mismatch, and the gateway's own success code carried the outcome.
     */
    private static String echoMismatch(Payment p, CardPaymentStatus status) {
        if (status.amountEcho() != null && p.getAmount() != null
                && status.amountEcho().compareTo(p.getAmount()) != 0) {
            return "amount: ledger " + p.getAmount() + " vs echo " + status.amountEcho();
        }
        if (status.currencyEcho() != null && p.getCurrency() != null
                && !status.currencyEcho().equalsIgnoreCase(p.getCurrency())) {
            return "currency: ledger " + p.getCurrency() + " vs echo " + status.currencyEcho();
        }
        if (status.merchantTransactionId() != null
                && !status.merchantTransactionId().equals(p.getPaymentReference())) {
            return "merchantTransactionId: ledger " + p.getPaymentReference()
                    + " vs echo " + status.merchantTransactionId();
        }
        return null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static UUID asUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
