package innbucks.paymentservice.service;

import innbucks.paymentservice.client.EcocashApiException;
import innbucks.paymentservice.client.EcocashApiTransientException;
import innbucks.paymentservice.client.EcocashChargeStatus;
import innbucks.paymentservice.client.EcocashEipClient;
import innbucks.paymentservice.client.EcocashProperties;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates an <b>EcoCash Instant Payment (EIP)</b> wallet charge — the
 * third rail beside the InnBucks 2D-code and ZimSwitch card rails, serving
 * any product wired behind an {@link OrderGateway}. Spec + safety analysis:
 * {@code docs/api/ecocash-eip.md}.
 *
 * <pre>
 *   1. gateway.fetch(orderRef): amount in cents, currency, payer msisdn
 *   2. gateway.extendHold(orderRef): the product hold provably outlives the
 *      prompt BEFORE any ledger write or upstream call
 *   3. Open a PENDING ledger row WITH the clientCorrelator already on it
 *   4. markEcocashChargeIssued: PENDING -> TOKEN_ISSUED, BEFORE the upstream
 *      call — inverted vs the other rails, and load-bearing: the EIP
 *      "instrument" is a PIN prompt ECOCASH delivers to the customer's
 *      phone, so a crash mid-call can leave a payable prompt live. With this
 *      ordering PENDING provably means "no upstream call attempted" (the
 *      stale sweep may close it) and TOKEN_ISSUED means "a charge may exist,
 *      keyed by this correlator" (the Query poller resolves it either way).
 *   5. POST the charge (amount as a MAJOR-unit JSON number — the client
 *      converts; clientCorrelator = the idempotency key, NEVER retried)
 *      - accepted    -> stays TOKEN_ISSUED; the customer approves on their
 *                       phone and the poller/webhook-triggered Query resolves
 *      - refused 4xx -> FAILED (no prompt was pushed; slot freed)
 *      - ambiguous   -> stays TOKEN_ISSUED — deliberately NOT failed: the
 *                       prompt may be live, and the Query resolves the truth
 *   6. Return PROCESSING — the FE shows "approve the prompt on your phone"
 *      and polls the order; there is no artifact to render on this rail.
 * </pre>
 *
 * <p><b>Resolution rules</b> (each pinned in the spec doc):
 * <ul>
 *   <li>{@code transactionOperationStatus} is the ONLY outcome field:
 *       COMPLETED = money moved; FAILED = subscriber rejected = positively
 *       unpaid (terminal FAILED, slot freed); everything else is open.</li>
 *   <li><b>Echo verification:</b> a COMPLETED read whose amount/currency
 *       echo disagrees with the ledger parks IN_DOUBT for an operator —
 *       never confirmed, never guessed.</li>
 *   <li><b>Still-pending past deadline + grace expires locally</b> — the
 *       code rail's "still New" rule: upstream POSITIVELY reports
 *       unapproved at the moment of the read. UNKNOWN/error never expires
 *       a row.</li>
 *   <li><b>NOT_FOUND past deadline + grace</b> is the positive "no charge
 *       exists" answer (crash-before-call leftover) — slot freed.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcocashPaymentService {

    /**
     * Slack past the local prompt TTL before a still-pending/NOT_FOUND row
     * is closed — absorbs clock skew and a poll cycle's worth of lag, same
     * constant discipline as the other two rails.
     */
    static final Duration PROMPT_EXPIRY_GRACE = Duration.ofMinutes(2);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentRecordService paymentRecordService;
    private final EcocashEipClient ecocashClient;
    private final EcocashProperties ecocashProperties;
    private final OrderGatewayRegistry orderGatewayRegistry;
    private final CodePaymentResolutionService resolutionService;
    private final PaymentMetrics metrics;

    /** Cell currency fallback, mirroring the other rails. */
    @Value("${innbucks.currency:USD}")
    private String cellCurrency;

    /** Whether the rail can talk to its gateway (poller predicate). */
    public boolean isRailConfigured() {
        return ecocashClient.isConfigured();
    }

    /** Everything the FE needs after a charge attempt (or a terminal verdict). */
    @Builder
    public record EcocashChargeOutcome(
            Payment payment,
            boolean failed,
            Instant promptExpiresAt,
            String upstreamCode,
            String upstreamMessage) {
    }

    /**
     * Start an EIP charge for any product order. Mirrors
     * {@link ZimswitchCardPaymentService#startCheckout} step for step and
     * throws the same {@link InvalidPaymentRequestException} vocabulary so
     * the controller's error mapping stays rail-agnostic.
     */
    public EcocashChargeOutcome startCharge(OrderType orderType, String orderRef) {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(orderRef, "orderRef");
        OrderGateway gateway = orderGatewayRegistry.forType(orderType);
        String noun = orderType == OrderType.BOOKING ? "booking" : "order";

        // Fail BEFORE claiming the order's payment slot when the rail can't
        // work. canStartCharge(), not isConfigured(): notifyUrl is an
        // upstream-MANDATORY charge field, so without it every charge is
        // refused — but the ledger row we would open holds the order's ONLY
        // payment slot across all three rails.
        if (!ecocashClient.canStartCharge()) {
            metrics.incEcocashCharge(ecocashClient.isConfigured()
                    ? "no_notify_url" : "unconfigured");
            if (ecocashClient.isConfigured()) {
                log.error("[ecocash] charge refused: ECOCASH_NOTIFY_URL is blank or not an absolute "
                        + "http(s) URL. Credentials ARE present, so the rail looks provisioned. Set it "
                        + "to the public edge webhook (https://…/foundry/payments/ecocash/notify).");
            }
            throw new InvalidPaymentRequestException(
                    "EcoCash payments are not available on this deployment", 503);
        }

        // One active payment per order — across ALL rails (the
        // uq_payment_active_order index is rail-agnostic).
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

        // Product hold must outlive the prompt BEFORE any ledger write or
        // upstream call.
        gateway.extendHold(orderRef);

        String paymentReference = SettlementReference.forTag(snapshot.settlementTag());
        String clientCorrelator = mintCorrelator();
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .paymentRail(PaymentRail.ECOCASH)
                .orderType(orderType)
                .orderRef(orderRef)
                .bookingId(orderType == OrderType.BOOKING ? asUuid(orderRef) : null)
                .customerMsisdn(customerMsisdn)
                .amount(BigDecimal.valueOf(amountCents, 2))
                .currency(currency)
                // On the row BEFORE any upstream call: the whole crash-safety
                // story of this rail hangs on the correlator never being
                // known only in memory.
                .ecocashClientCorrelator(clientCorrelator)
                .build();
        Payment opened;
        try {
            opened = paymentRecordService.openPending(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("[ecocash] concurrent payment refused by active-order index orderType={} orderRef={}",
                    orderType, orderRef);
            throw new InvalidPaymentRequestException(
                    "A payment for this " + noun + " is already in progress or completed", 409);
        }

        // TOKEN_ISSUED BEFORE the upstream call — see the class javadoc.
        Instant promptExpiresAt = Instant.now().plus(ecocashProperties.getChargeTtl());
        paymentRecordService.markEcocashChargeIssued(opened.getId(), clientCorrelator, promptExpiresAt);

        try {
            EcocashChargeStatus accepted = ecocashClient.charge(
                    clientCorrelator, customerMsisdn, amountCents, currency, paymentReference);
            paymentRecordService.recordEcocashReference(opened.getId(), accepted.ecocashReference());
            metrics.incEcocashCharge("charged");
            log.info("[ecocash] charge issued paymentReference={} orderType={} clientCorrelator={} promptExpiresAt={}",
                    paymentReference, orderType, clientCorrelator, promptExpiresAt);
        } catch (EcocashApiException e) {
            // Active 4xx refusal: no prompt was pushed, no money can move —
            // closing FAILED frees the slot for a clean retry.
            log.warn("[ecocash] charge refused paymentReference={} status={}: {}",
                    paymentReference, e.getStatusCode(), e.getMessage());
            paymentRecordService.markFailed(opened.getId(), "ecocash_refused", e.getMessage());
            metrics.incEcocashCharge("refused");
            return EcocashChargeOutcome.builder()
                    .payment(opened)
                    .failed(true)
                    .upstreamCode("ecocash_refused")
                    .upstreamMessage("EcoCash could not start this payment; please try again")
                    .build();
        } catch (EcocashApiTransientException e) {
            // Ambiguous: the charge MAY have gone through and a PIN prompt
            // may be live on the customer's phone. Deliberately NOT failed
            // and NEVER retried — the row stays TOKEN_ISSUED and the Query
            // poller resolves the truth (NOT_FOUND past the deadline closes
            // it if the charge never existed).
            paymentRecordService.noteEvent(opened.getId(),
                    "Charge outcome ambiguous (" + e.getMessage() + ") — a prompt may be live; "
                            + "the Query poller will resolve this row");
            metrics.incEcocashCharge("ambiguous");
            log.warn("[ecocash] charge ambiguous paymentReference={} clientCorrelator={} — leaving row for the poller: {}",
                    paymentReference, clientCorrelator, e.getMessage());
        }

        return EcocashChargeOutcome.builder()
                .payment(opened)
                .failed(false)
                .promptExpiresAt(promptExpiresAt)
                .build();
    }

    /** Outcome of a Query pass / instant check on an open EIP charge. */
    public enum EcocashCheckOutcome { PAID, EXPIRED, PENDING }

    /**
     * Resolve an open EIP charge by querying the gateway — THE resolver for
     * this rail, shared by the reconciler poll, the customer-triggered
     * instant check (replay POST) and the notify-webhook trigger, so the
     * money rules cannot drift between the three paths.
     */
    public EcocashCheckOutcome resolveOpenCharge(Payment p) {
        if (p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED
                || p.getEcocashClientCorrelator() == null
                || p.getEcocashClientCorrelator().isBlank()) {
            return EcocashCheckOutcome.PENDING;
        }

        EcocashChargeStatus status;
        try {
            status = ecocashClient.query(p.getCustomerMsisdn(), p.getEcocashClientCorrelator());
        } catch (RuntimeException e) {
            metrics.incEcocashResolution("error");
            log.warn("[ecocash] query unavailable paymentReference={} cause={}",
                    p.getPaymentReference(), e.getMessage());
            return EcocashCheckOutcome.PENDING;
        }

        switch (status.outcome()) {
            case COMPLETED -> {
                return onPaidRead(p, status);
            }
            case FAILED -> {
                // The subscriber rejected the prompt — the positive "no money
                // moved" answer. Terminal FAILED frees the slot for a retry.
                paymentRecordService.markFailed(p.getId(), "subscriber_rejected",
                        "EcoCash reports the charge FAILED (subscriber rejected or prompt lapsed upstream)");
                metrics.incEcocashResolution("failed");
                log.info("[ecocash] charge failed upstream paymentReference={} — slot freed",
                        p.getPaymentReference());
                return EcocashCheckOutcome.EXPIRED;
            }
            case PENDING -> {
                // Upstream POSITIVELY reports unapproved — no money can be in
                // flight at the moment of this read. Past our deadline +
                // grace the prompt itself is long dead: expire locally, the
                // code rail's "still New" rule.
                Instant deadline = (p.getCodeExpiresAt() == null
                        ? p.getCreatedAt().plus(ecocashProperties.getChargeTtl())
                        : p.getCodeExpiresAt())
                        .plus(PROMPT_EXPIRY_GRACE);
                if (Instant.now().isAfter(deadline)) {
                    paymentRecordService.markExpired(p.getId(),
                            "Prompt deadline passed and EcoCash still reports "
                                    + status.rawStatus() + " — closing unpaid");
                    metrics.incEcocashResolution("expired");
                    return EcocashCheckOutcome.EXPIRED;
                }
                metrics.incEcocashResolution("still_pending");
                return EcocashCheckOutcome.PENDING;
            }
            case NOT_FOUND -> {
                // Normal for a beat after issuing; later it is the
                // crash-before-call signature. Past the deadline + grace it
                // is the positive "no charge exists" answer — free the slot.
                Instant deadline = (p.getCodeExpiresAt() == null
                        ? p.getCreatedAt().plus(ecocashProperties.getChargeTtl())
                        : p.getCodeExpiresAt())
                        .plus(PROMPT_EXPIRY_GRACE);
                if (Instant.now().isAfter(deadline)) {
                    paymentRecordService.markExpired(p.getId(),
                            "Prompt deadline passed and EcoCash reports no such transaction — "
                                    + "no charge was ever created; closing unpaid");
                    metrics.incEcocashResolution("expired");
                    return EcocashCheckOutcome.EXPIRED;
                }
                metrics.incEcocashResolution("not_found_pending");
                return EcocashCheckOutcome.PENDING;
            }
            case UNKNOWN -> {
                // NEVER guess. The customer may have paid; expiring would
                // free the slot and invite a double charge. The row stays put
                // and the metric drips — sustained unknowns are an operator
                // page.
                metrics.incEcocashResolution("unknown");
                log.warn("[ecocash] status unresolvable paymentReference={} raw='{}' — leaving row",
                        p.getPaymentReference(), status.rawStatus());
                return EcocashCheckOutcome.PENDING;
            }
        }
        return EcocashCheckOutcome.PENDING;
    }

    /**
     * A COMPLETED read. The Query is repeatable (unlike ZimSwitch's one-shot
     * status), so the code rail's confirm-then-mark order applies; the echo
     * guard still runs first — a mismatched amount/currency parks IN_DOUBT
     * for an operator, never a confirm.
     */
    private EcocashCheckOutcome onPaidRead(Payment p, EcocashChargeStatus status) {
        String reference = status.ecocashReference() != null
                ? status.ecocashReference() : p.getEcocashReference();
        String mismatch = echoMismatch(p, status);
        if (mismatch != null) {
            metrics.incEcocashResolution("echo_mismatch");
            log.error("[ecocash] COMPLETED READ WITH ECHO MISMATCH — parking IN_DOUBT for operator "
                            + "paymentReference={} clientCorrelator={} ecocashReference={} {}",
                    p.getPaymentReference(), p.getEcocashClientCorrelator(), reference, mismatch);
            paymentRecordService.markInDoubt(p.getId(),
                    "EcoCash reports COMPLETED but the echo does not match the ledger (" + mismatch
                            + ") — EcoCash ref " + reference
                            + ". Operator must reconcile against EcoCash records before any confirm/refund.");
            return EcocashCheckOutcome.PENDING;
        }

        paymentRecordService.recordEcocashReference(p.getId(), reference);

        ConfirmOutcome outcome = resolutionService.confirmOrder(p);
        if (outcome.succeeded()) {
            paymentRecordService.markSucceeded(p.getId(), reference, outcome.confirmationNumber());
            metrics.incEcocashResolution("paid");
            log.info("[ecocash] paid + order confirmed paymentReference={} ecocashReference={} confirmation={}",
                    p.getPaymentReference(), reference, outcome.confirmationNumber());
        } else {
            paymentRecordService.markCompletedUnconfirmed(p.getId(), reference,
                    "Customer approved the EcoCash charge but the order confirm failed: " + outcome.reason());
            metrics.incEcocashResolution("paid_unconfirmed");
            log.error("ECOCASH PAID BUT ORDER CONFIRM FAILED — confirm-retry will keep trying "
                            + "paymentReference={} ecocashReference={} reason={}",
                    p.getPaymentReference(), reference, outcome.reason());
        }
        return EcocashCheckOutcome.PAID;
    }

    /**
     * Echo verification on a COMPLETED read: {@code totalAmountCharged} (the
     * post-approval fact), the charge amount echo and the currency must match
     * the ledger. Null echoes pass — absent fields are not evidence of a
     * mismatch (the doc's samples null out plenty), and the status itself
     * carried the outcome.
     */
    private static String echoMismatch(Payment p, EcocashChargeStatus status) {
        if (status.totalAmountCharged() != null && p.getAmount() != null
                && status.totalAmountCharged().signum() != 0
                && status.totalAmountCharged().compareTo(p.getAmount()) != 0) {
            return "totalAmountCharged: ledger " + p.getAmount() + " vs echo " + status.totalAmountCharged();
        }
        if (status.amountEcho() != null && p.getAmount() != null
                && status.amountEcho().compareTo(p.getAmount()) != 0) {
            return "amount: ledger " + p.getAmount() + " vs echo " + status.amountEcho();
        }
        if (status.currencyEcho() != null && p.getCurrency() != null
                && !status.currencyEcho().equalsIgnoreCase(p.getCurrency())) {
            return "currency: ledger " + p.getCurrency() + " vs echo " + status.currencyEcho();
        }
        return null;
    }

    /**
     * Numeric correlator, unique per charge: epoch millis + 6 random digits.
     * The doc's samples are numeric timestamp strings, so a numeric shape is
     * the conservative choice; uniqueness is belt-and-braces enforced by the
     * partial unique index on the column and by EcoCash's own duplicate
     * rejection.
     */
    private static String mintCorrelator() {
        return System.currentTimeMillis() + String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
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
