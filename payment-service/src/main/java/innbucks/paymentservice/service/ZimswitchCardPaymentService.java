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
 *   2. gateway.extendHold(orderRef): the product hold provably outlives the
 *      checkout BEFORE any ledger write or upstream call
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
 *   6. The card status poller (and the customer-triggered instant check on
 *      replay) resolves the row via GET /v1/checkouts/{id}/payment.
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
 *   <li><b>Declines don't close the row:</b> the checkout stays alive
 *       upstream and the shopper may retry another card on it (the
 *       documented multi-transaction reuse). The decline is journalled; the
 *       row expires via the NOT_FOUND-past-deadline rule if never paid.</li>
 *   <li><b>Throttle:</b> two status reads per checkout per minute, enforced
 *       through the persisted {@code card_status_checked_at} stamp shared by
 *       poller and instant check.</li>
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
     * checkout, making "no payment found" a POSITIVE never-paid answer.
     */
    static final Duration CHECKOUT_EXPIRY_GRACE = Duration.ofMinutes(2);

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
        if (!zimswitchClient.isConfigured()) {
            metrics.incCardCheckout("unconfigured");
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
        String currency = snapshot.currency();
        if (currency == null || currency.isBlank()) currency = cellCurrency;
        String customerMsisdn = snapshot.payerMsisdn();
        if (customerMsisdn == null || customerMsisdn.isBlank()) {
            throw new InvalidPaymentRequestException(
                    capitalize(noun) + " has no payer phone number — create the " + noun
                            + " with a phone number before paying", 400);
        }

        // Product hold must outlive the checkout BEFORE any ledger write or
        // upstream call — a refusal means the order already lapsed, with
        // ZERO money moved.
        gateway.extendHold(orderRef);

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

    /** Outcome of a status poll / instant check on an open card checkout. */
    public enum CardCheckOutcome { PAID, EXPIRED, PENDING }

    /**
     * Resolve an open card checkout by querying the gateway — THE resolver
     * for this rail, shared by the reconciler poll and the customer-triggered
     * instant check so the money rules cannot drift between them.
     *
     * @param minGap minimum age of the previous status read before another
     *               is allowed (the two-per-minute throttle share for this
     *               caller); a fresher stamp skips the read and reports
     *               PENDING
     */
    public CardCheckOutcome resolveOpenCheckout(Payment p, Duration minGap) {
        if (p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED
                || p.getCheckoutId() == null || p.getCheckoutId().isBlank()) {
            return CardCheckOutcome.PENDING;
        }
        Instant lastChecked = p.getCardStatusCheckedAt();
        if (lastChecked != null && lastChecked.isAfter(Instant.now().minus(minGap))) {
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
            log.warn("[zimswitch-card] status read unavailable paymentReference={} cause={}",
                    p.getPaymentReference(), e.getMessage());
            return CardCheckOutcome.PENDING;
        }

        switch (status.outcome()) {
            case SUCCESS, SUCCESS_MANUAL_REVIEW -> {
                return onPaidRead(p, status);
            }
            case REJECTED -> {
                // A decline closes the ATTEMPT, not the checkout: the shopper
                // can retry another card on the same checkout until it
                // expires (documented multi-transaction reuse). Journal the
                // decline verbatim; the row lapses via NOT_FOUND-past-
                // deadline if never paid.
                metrics.incCardResolution("declined_noted");
                paymentRecordService.noteEvent(p.getId(),
                        "Card attempt declined by gateway: " + status.resultCode()
                                + (status.resultMessage() != null ? " (" + status.resultMessage() + ")" : "")
                                + " — checkout stays open for a retry");
                log.info("[zimswitch-card] attempt declined paymentReference={} resultCode={} — checkout stays open",
                        p.getPaymentReference(), status.resultCode());
                return CardCheckOutcome.PENDING;
            }
            case PENDING -> {
                metrics.incCardResolution("still_pending");
                return CardCheckOutcome.PENDING;
            }
            case CHECKOUT_NOT_FOUND -> {
                // Normal while the shopper still has the form open. Past the
                // deadline + grace (i.e. past the gateway's own 30-minute
                // ceiling) it is a POSITIVE never-paid answer: no transaction
                // exists, none can be created anymore — free the slot. A row
                // missing its local deadline (defensive; markCardCheckoutIssued
                // always stamps one) falls back to the gateway CEILING from
                // creation, never earlier — expiring while the checkout is
                // still payable upstream would orphan a late payment.
                Instant deadline = (p.getCodeExpiresAt() == null
                        ? p.getCreatedAt().plus(Duration.ofMinutes(30)) : p.getCodeExpiresAt())
                        .plus(CHECKOUT_EXPIRY_GRACE);
                if (Instant.now().isAfter(deadline)) {
                    paymentRecordService.markExpired(p.getId(),
                            "Checkout ceiling passed and the gateway reports no payment — closing unpaid");
                    metrics.incCardResolution("expired");
                    return CardCheckOutcome.EXPIRED;
                }
                metrics.incCardResolution("not_found_pending");
                return CardCheckOutcome.PENDING;
            }
        }
        return CardCheckOutcome.PENDING;
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
    private CardCheckOutcome onPaidRead(Payment p, CardPaymentStatus status) {
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
                "Card payment succeeded (result " + status.resultCode() + "); confirming order");

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
