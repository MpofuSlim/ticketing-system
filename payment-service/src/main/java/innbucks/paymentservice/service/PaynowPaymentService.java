package innbucks.paymentservice.service;

import innbucks.paymentservice.client.PaynowApiException;
import innbucks.paymentservice.client.PaynowApiTransientException;
import innbucks.paymentservice.client.PaynowClient;
import innbucks.paymentservice.client.PaynowInitiation;
import innbucks.paymentservice.client.PaynowMessage;
import innbucks.paymentservice.client.PaynowNotSentException;
import innbucks.paymentservice.client.PaynowProperties;
import innbucks.paymentservice.client.PaynowStatus;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.PaymentRail;
import innbucks.paymentservice.order.ConfirmOutcome;
import innbucks.paymentservice.order.OrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Paynow payments ({@code docs/api/paynow.md}): the methods our other rails
 * do not carry. Tickets only for now (owner's decision, 2026-11-25).
 *
 * <ul>
 *   <li>{@link PaymentRail#PAYNOW_CARD}: web checkout. Paynow answers with a
 *       page; the website sends the customer there to pay by card
 *       (Visa/Mastercard or ZimSwitch, whatever the merchant's integration has
 *       switched on), and Paynow sends them back to the website.</li>
 *   <li>{@link PaymentRail#ONEMONEY}: express checkout. A prompt on the
 *       customer's phone, like EcoCash.</li>
 *   <li>{@link PaymentRail#OMARI}: express checkout. O'mari texts the customer
 *       a code; they type it into our checkout and we pass it on
 *       ({@link #submitOtp}).</li>
 * </ul>
 *
 * <pre>
 *   1. gateway.fetch(orderRef); the order's currency picks the integration
 *   2. gateway.extendHold(deadline + grace) BEFORE any ledger write or call
 *   3. open PENDING with our merchant trace already on the row
 *   4. PENDING -> TOKEN_ISSUED BEFORE the call (EcoCash's order): a OneMoney
 *      prompt may be live even when Paynow's reply is lost
 *   5. initiate, ONCE (never retried)
 *      - Ok          record the page / poll / O'mari URLs; AWAITING_PAYMENT
 *      - Error       Paynow refused, nothing was created: FAILED, slot freed
 *      - no answer   ONEMONEY: left open, a prompt may be live; the status
 *                    check looks it up by its trace. PAYNOW_CARD and OMARI:
 *                    closed FAILED at once. Without Paynow's reply the customer
 *                    has no page to pay on and nowhere to type the code, so
 *                    no payment can be made against it.
 * </pre>
 *
 * <p><b>Resolution</b> ({@link #resolveOpen}): a poll (or, with no poll URL, a
 * trace) is the answer we act on. Paynow's result-URL update is only a trigger
 * for it, as Paynow recommends. A paid status whose amount or reference is not
 * ours parks IN_DOUBT; a status we do not know is never guessed.
 *
 * <p><b>Money after the fact goes to a person</b> (owner's decision,
 * 2026-11-25): a payment paid after we closed it, or refunded or disputed
 * after the booking was confirmed, changes nothing automatically. It is
 * journalled on the payment and counted on
 * {@code payment.payments.paynow_resolution} (alert
 * {@code PaynowPaymentNeedsOperator}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaynowPaymentService {

    /** Slack past our deadline before an unpaid payment is closed, as on the other rails. */
    static final Duration EXPIRY_GRACE = Duration.ofMinutes(2);

    /** {@code upstream_error_code} of a payment the customer's wallet refused at the start (e.g. insufficient balance). */
    public static final String DECLINED_CODE = "paynow_declined";
    /** A payment Paynow reports Cancelled or Failed. */
    public static final String CANCELLED_CODE = "paynow_cancelled";
    /** Paynow refused to start a card payment. */
    static final String REFUSED_CODE = "paynow_refused";
    /** Paynow's reply never arrived, and the customer had nothing to pay with. */
    static final String UNREACHABLE_CODE = "paynow_unreachable";

    static final Set<PaymentRail> PHONE_RAILS = EnumSet.of(PaymentRail.ONEMONEY, PaymentRail.OMARI);

    /** Every Paynow rail. */
    static final Set<PaymentRail> RAILS = EnumSet.of(PaymentRail.PAYNOW_CARD, PaymentRail.ONEMONEY, PaymentRail.OMARI);

    /** How long the sweep leaves a payment whose initiate reply was lost before looking it up by its trace. */
    static final Duration NO_REPLY_GRACE = Duration.ofSeconds(30);

    /** Past connect + read timeout, what a start can still spend waiting: a pooled lease and the TLS handshake. */
    static final Duration START_REPLY_MARGIN = Duration.ofSeconds(15);

    /** Paynow's published test-mode numbers ({@code docs/api/paynow.md}), in the local form we send. */
    static final Set<String> PAYNOW_TEST_NUMBERS = Set.of("0771111111", "0772222222", "0773333333", "0774444444");

    private final PaymentRecordService paymentRecordService;
    private final PaynowClient paynowClient;
    private final PaynowProperties properties;
    private final OrderGatewayRegistry orderGatewayRegistry;
    private final CodePaymentResolutionService resolutionService;
    private final PaymentRepository paymentRepository;
    private final PaymentMetrics metrics;

    @Value("${innbucks.currency:USD}")
    private String cellCurrency;

    /** What customers call each Paynow rail. */
    public static String methodLabel(PaymentRail rail) {
        return switch (rail) {
            case PAYNOW_CARD -> "Card payment";
            case ONEMONEY -> "OneMoney";
            case OMARI -> "O'mari";
            default -> rail.name();
        };
    }

    /** Paynow's own words for a payment the customer's wallet refused at the start; null otherwise. */
    public static String declineReason(Payment p) {
        if (p == null || p.getPaymentRail() == null || !p.getPaymentRail().isPaynow()
                || !DECLINED_CODE.equals(p.getUpstreamErrorCode())) {
            return null;
        }
        String m = p.getUpstreamErrorMessage();
        return m == null || m.isBlank() ? null : m;
    }

    /** Whether the poller has anything to talk to. */
    public boolean isRailConfigured() {
        return paynowClient.hasAnyIntegration();
    }

    /**
     * A started (or refused) Paynow payment.
     *
     * @param redirectUrl  PAYNOW_CARD: Paynow's page for the customer
     * @param otpReference OMARI: the reference of the code O'mari texted
     * @param expiresAt    when we stop waiting for this payment
     * @param failureStatus HTTP status for a failed start (400 refused / 502 no answer)
     */
    public record StartOutcome(
            Payment payment,
            boolean failed,
            int failureStatus,
            String failureMessage,
            String redirectUrl,
            String otpReference,
            Instant expiresAt) {

        static StartOutcome failed(Payment p, int status, String message) {
            return new StartOutcome(p, true, status, message, null, null, null);
        }
    }

    /** Start a Paynow payment for a ticket booking. Throws the same refusals as the other rails. */
    public StartOutcome start(PaymentRail rail, OrderType orderType, String orderRef) {
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(orderRef, "orderRef");
        if (!rail.isPaynow()) {
            throw new IllegalArgumentException(rail + " is not a Paynow rail");
        }
        String label = methodLabel(rail);
        if (orderType != OrderType.BOOKING) {
            metrics.incPaynowStart(rail.name(), "not_tickets");
            throw new InvalidPaymentRequestException(
                    label + " is only available for tickets for now — please use another payment method", 422);
        }
        if (!paynowClient.hasAnyIntegration()) {
            metrics.incPaynowStart(rail.name(), "unconfigured");
            throw new InvalidPaymentRequestException(label + " is not available on this deployment", 503);
        }
        if (paymentRecordService.hasActiveOrSucceededPayment(orderType, orderRef)) {
            throw new InvalidPaymentRequestException(
                    "A payment for this booking is already in progress or completed", 409);
        }
        if (PHONE_RAILS.contains(rail)) {
            requireNoRecentDecline(rail, orderType, orderRef);
        }

        OrderGateway gateway = orderGatewayRegistry.forType(orderType);
        OrderSnapshot snapshot = gateway.fetch(orderRef);
        if (!snapshot.payable()) {
            throw new InvalidPaymentRequestException(
                    "This booking is not awaiting payment — it may already be paid, cancelled or expired", 409);
        }
        long amountCents = snapshot.amountCents();
        if (amountCents <= 0) {
            throw new InvalidPaymentRequestException("Booking has no positive amount; cannot request payment", 422);
        }
        String currency = canonicalCurrency(snapshot.currency() != null ? snapshot.currency() : cellCurrency);
        boolean ready = rail == PaymentRail.PAYNOW_CARD
                ? paynowClient.canStartWeb(currency) : paynowClient.canStartExpress(currency);
        if (!ready) {
            if (paynowClient.isConfigured(currency)) {
                metrics.incPaynowStart(rail.name(), "unconfigured");
                log.error("[paynow] {} refused: the {} integration is set but the rail is half-provisioned "
                        + "(result URL, return URL or auth email missing) — see docs/api/paynow.md", rail, currency);
                throw new InvalidPaymentRequestException(label + " is not available on this deployment", 503);
            }
            metrics.incPaynowStart(rail.name(), "unsupported_currency");
            throw new InvalidPaymentRequestException(
                    label + " cannot take payment in " + currency + " — please use another payment method", 422);
        }
        String msisdn = snapshot.payerMsisdn();
        if (msisdn == null || msisdn.isBlank()) {
            throw new InvalidPaymentRequestException(
                    "Booking has no payer phone number — create the booking with a phone number before paying", 400);
        }
        String phone = null;
        if (PHONE_RAILS.contains(rail)) {
            phone = localPhone(msisdn);
            if (phone == null) {
                throw new InvalidPaymentRequestException(
                        label + " needs a Zimbabwean phone number on the booking", 400);
            }
            if (rail == PaymentRail.ONEMONEY && PAYNOW_TEST_NUMBERS.contains(phone)
                    && !properties.isTestNumbersAllowed()) {
                // A test-mode integration reports these PAID with no money
                // moved; on a live one they are Econet numbers, never OneMoney.
                metrics.incPaynowStart(rail.name(), "test_number");
                log.warn("[paynow] OneMoney refused for one of Paynow's test numbers orderRef={}", orderRef);
                throw new InvalidPaymentRequestException(
                        label + " cannot be used with this number — please pay another way", 400);
            }
            requirePromptRoomFor(rail, msisdn);
        }

        Duration ttl = rail == PaymentRail.PAYNOW_CARD ? properties.getCardTtl() : properties.getMobileTtl();
        Instant expiresAt = Instant.now().plus(ttl);
        gateway.extendHold(orderRef, expiresAt.plus(EXPIRY_GRACE));

        String paymentReference = SettlementReference.forTag(snapshot.settlementTag());
        String trace = newTrace();
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .paymentRail(rail)
                .orderType(orderType)
                .orderRef(orderRef)
                .bookingId(asUuid(orderRef))
                .customerMsisdn(msisdn)
                .amount(BigDecimal.valueOf(amountCents, 2))
                .currency(currency)
                // On the row BEFORE the call: a payment whose reply is lost is
                // found again by this trace.
                .paynowTrace(trace)
                .build();
        Payment opened;
        try {
            opened = paymentRecordService.openPending(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (!PaymentRecordService.isActiveOrderConflict(e)) {
                log.error("[paynow] ledger refused the payment row (not the active-order index) orderRef={}",
                        orderRef, e);
                throw e;
            }
            throw new InvalidPaymentRequestException(
                    "A payment for this booking is already in progress or completed", 409);
        }
        paymentRecordService.markPaynowIssued(opened.getId(), trace, expiresAt);

        try {
            PaynowInitiation init = rail == PaymentRail.PAYNOW_CARD
                    ? paynowClient.initiateWeb(currency, paymentReference, amountCents, additionalInfo(orderType),
                            returnUrlFor(opened.getId()), trace)
                    : paynowClient.initiateExpress(currency, paymentReference, amountCents, additionalInfo(orderType),
                            trace, rail == PaymentRail.ONEMONEY ? "onemoney" : "omari", phone);
            paymentRecordService.recordPaynowInitiation(opened.getId(), init.pollUrl(), init.browserUrl(),
                    init.paynowReference(), init.remoteOtpUrl(), init.otpReference());
            boolean nothingToActOn = (rail == PaymentRail.PAYNOW_CARD && init.browserUrl() == null)
                    || (rail == PaymentRail.OMARI && init.remoteOtpUrl() == null);
            if (nothingToActOn) {
                paymentRecordService.markFailed(opened.getId(), UNREACHABLE_CODE,
                        "Paynow started the payment but gave " + (rail == PaymentRail.OMARI
                                ? "no URL for the customer's code" : "no page to pay on"));
                metrics.incPaynowStart(rail.name(), "refused");
                return StartOutcome.failed(opened, 502,
                        label + " could not be started — please try again or pay another way");
            }
            metrics.incPaynowStart(rail.name(), "started");
            log.info("[paynow] {} started paymentReference={} paynowReference={} expiresAt={}",
                    rail, paymentReference, init.paynowReference(), expiresAt);
            return new StartOutcome(opened, false, 0, null, init.browserUrl(), init.otpReference(), expiresAt);
        } catch (PaynowApiException e) {
            // Paynow answered no: nothing was created, so closing frees the slot.
            boolean wallet = PHONE_RAILS.contains(rail);
            paymentRecordService.markFailed(opened.getId(), wallet ? DECLINED_CODE : REFUSED_CODE,
                    e.paynowError() != null ? e.paynowError() : e.getMessage());
            metrics.incPaynowStart(rail.name(), wallet ? "declined" : "refused");
            String message = wallet && e.paynowError() != null
                    ? label + ": " + sentence(e.paynowError())
                    : label + " could not start this payment — please try again or pay another way";
            return StartOutcome.failed(opened, 400, message);
        } catch (PaynowNotSentException e) {
            // Nothing reached Paynow (our circuit breaker refused, or no
            // connection opened), so nothing exists there on any rail: close
            // at once and free the booking, rather than wait on a prompt that
            // was never sent.
            metrics.incPaynowStart(rail.name(), "not_sent");
            paymentRecordService.markFailed(opened.getId(), UNREACHABLE_CODE,
                    "Paynow was not reached, nothing was sent (" + e.getMessage() + ")");
            log.warn("[paynow] {} start never reached Paynow paymentReference={} — closed: {}",
                    rail, paymentReference, e.getMessage());
            return StartOutcome.failed(opened, 502,
                    label + " could not be started — please try again in a moment or pay another way");
        } catch (PaynowApiTransientException e) {
            metrics.incPaynowStart(rail.name(), "ambiguous");
            if (rail == PaymentRail.ONEMONEY) {
                // The prompt may be live on the customer's phone: leave the row
                // open; the status check looks it up by its trace.
                paymentRecordService.noteEvent(opened.getId(), "Initiate outcome ambiguous (" + e.getMessage()
                        + ") — a OneMoney prompt may be live; the status check will look it up by its trace");
                log.warn("[paynow] OneMoney start ambiguous paymentReference={} — left for the status check: {}",
                        paymentReference, e.getMessage());
                return new StartOutcome(opened, false, 0, null, null, null, expiresAt);
            }
            // No page to pay on, nowhere to type the code: nothing can be paid
            // against this attempt, so it closes and frees the booking.
            paymentRecordService.markFailed(opened.getId(), UNREACHABLE_CODE,
                    "No usable answer from Paynow (" + e.getMessage() + ") — the customer got nothing to pay with");
            log.warn("[paynow] {} start got no answer paymentReference={} — closed: {}",
                    rail, paymentReference, e.getMessage());
            return StartOutcome.failed(opened, 502,
                    label + " could not be started — please try again in a moment or pay another way");
        }
    }

    // ---------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------

    /** What a status check did. */
    public enum CheckOutcome {
        /** Paid: the booking is confirmed (or COMPLETED_UNCONFIRMED). */
        PAID,
        /** Closed unpaid: the slot is free. */
        CLOSED,
        /** Still open, or parked for an operator. */
        OPEN,
        /** Paynow could not be asked. */
        ERROR
    }

    /** Who asked for a check: everyone but a Paynow update or an O'mari code waits out the minimum gap. */
    public enum Trigger { RESULT_UPDATE, OTP, CUSTOMER, SWEEP }

    /**
     * Check an open Paynow payment: THE resolver for these rails, shared by
     * the result-URL update, the O'mari code reply, the customer's re-POST
     * and the sweep, so the money rules cannot drift between them.
     */
    public CheckOutcome resolveOpen(Payment stale, Trigger trigger) {
        // The copy in hand can be minutes old (the sweep reads a page, then
        // checks each in turn): decide on the row as it is now.
        Payment p = paymentRepository.findById(stale.getId()).orElse(stale);
        if (p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED || p.getPaymentRail() == null
                || !p.getPaymentRail().isPaynow()) {
            return CheckOutcome.OPEN;
        }
        if ((trigger == Trigger.CUSTOMER || trigger == Trigger.SWEEP) && p.getPaynowCheckedAt() != null
                && p.getPaynowCheckedAt().plus(properties.getCheckMinGap()).isAfter(Instant.now())) {
            return CheckOutcome.OPEN;
        }
        paymentRecordService.stampPaynowChecked(p.getId());
        PaynowStatus status;
        try {
            if (p.getPaynowPollUrl() != null) {
                status = paynowClient.poll(p.getCurrency(), p.getPaynowPollUrl());
            } else if (p.getPaynowTrace() != null) {
                status = paynowClient.trace(p.getCurrency(), p.getPaynowTrace());
            } else {
                metrics.incPaynowResolution("unknown");
                log.error("[paynow] open payment with neither a poll URL nor a trace paymentReference={}",
                        p.getPaymentReference());
                return CheckOutcome.OPEN;
            }
        } catch (RuntimeException e) {
            metrics.incPaynowResolution("error");
            log.warn("[paynow] status unavailable paymentReference={} cause={}", p.getPaymentReference(), e.getMessage());
            return CheckOutcome.ERROR;
        }
        paymentRecordService.recordPaynowSeen(p.getId(), status.paynowReference(), status.pollUrl());
        String label = methodLabel(p.getPaymentRail());
        return switch (status.outcome()) {
            case PAID -> onPaid(p, status);
            case CANCELLED -> {
                paymentRecordService.markFailed(p.getId(), CANCELLED_CODE,
                        "Paynow reports the payment " + status.rawStatus());
                metrics.incPaynowResolution("cancelled");
                log.info("[paynow] {} payment {} paymentReference={} — slot freed",
                        label, status.rawStatus(), p.getPaymentReference());
                yield CheckOutcome.CLOSED;
            }
            case OPEN, NOT_FOUND -> {
                if (pastDeadline(p)) {
                    paymentRecordService.markExpired(p.getId(), status.outcome() == PaynowStatus.Outcome.OPEN
                            ? "Deadline passed and Paynow still reports " + status.rawStatus() + " — closing unpaid"
                            : "Deadline passed and Paynow finds no payment for our trace — closing unpaid");
                    metrics.incPaynowResolution("expired");
                    yield CheckOutcome.CLOSED;
                }
                metrics.incPaynowResolution(status.outcome() == PaynowStatus.Outcome.OPEN
                        ? "still_open" : "not_found_open");
                yield CheckOutcome.OPEN;
            }
            case REVERSED -> {
                paymentRecordService.markInDoubt(p.getId(), "Paynow reports " + status.rawStatus()
                        + " on a payment we never confirmed (Paynow ref " + status.paynowReference()
                        + "). Operator: check Paynow's records before any confirm or refund.");
                operatorCase(p, "reversed_before_paid", "Paynow reports " + status.rawStatus()
                        + " on an open payment (Paynow ref " + status.paynowReference() + "). Parked IN_DOUBT;"
                        + " no tickets were issued.");
                yield CheckOutcome.OPEN;
            }
            case UNKNOWN -> {
                // NEVER guess: the customer may have paid.
                metrics.incPaynowResolution("unknown");
                log.warn("[paynow] unknown status '{}' paymentReference={} — leaving the payment open",
                        status.rawStatus(), p.getPaymentReference());
                yield CheckOutcome.OPEN;
            }
        };
    }

    private CheckOutcome onPaid(Payment p, PaynowStatus status) {
        String mismatch = echoMismatch(p, status);
        String paynowRef = status.paynowReference() != null ? status.paynowReference() : p.getPaynowReference();
        if (mismatch != null) {
            metrics.incPaynowResolution("echo_mismatch");
            log.error("[paynow] PAID STATUS WITH ECHO MISMATCH — parking IN_DOUBT paymentReference={} paynowReference={} {}",
                    p.getPaymentReference(), paynowRef, mismatch);
            paymentRecordService.markInDoubt(p.getId(), "Paynow reports " + status.rawStatus()
                    + " but the echo does not match the ledger (" + mismatch + ") — Paynow ref " + paynowRef
                    + ". Operator must reconcile against Paynow's records before any confirm or refund.");
            return CheckOutcome.OPEN;
        }
        // The money fact FIRST, and only while the row is still open: the
        // copy in hand can be older than the row (the sweep reads a page, then
        // polls each in turn), and a row another trigger closed meanwhile
        // must not get its booking confirmed. That is the operator's case.
        if (!paymentRecordService.claimPaid(p.getId(), paynowRef,
                "Paynow reports the payment " + status.rawStatus() + "; confirming the booking")) {
            return onPaidButNotClaimed(p, status, paynowRef);
        }
        ConfirmOutcome outcome = resolutionService.confirmOrder(p);
        if (outcome.succeeded()) {
            paymentRecordService.resolveUnconfirmed(p.getId(), outcome.confirmationNumber(),
                    "Paynow payment confirmed the booking");
            metrics.incPaynowResolution("paid");
            log.info("[paynow] paid + booking confirmed paymentReference={} paynowReference={} status={} confirmation={}",
                    p.getPaymentReference(), paynowRef, status.rawStatus(), outcome.confirmationNumber());
        } else {
            paymentRecordService.noteEvent(p.getId(),
                    "Customer paid through Paynow but the booking confirm failed: " + outcome.reason());
            metrics.incPaynowResolution("paid_unconfirmed");
            log.error("PAYNOW PAID BUT BOOKING CONFIRM FAILED — confirm-retry will keep trying paymentReference={} "
                    + "paynowReference={} reason={}", p.getPaymentReference(), paynowRef, outcome.reason());
        }
        return CheckOutcome.PAID;
    }

    /** A paid status for a row this call did not move: read the row as it is now. */
    private CheckOutcome onPaidButNotClaimed(Payment stale, PaynowStatus status, String paynowRef) {
        Payment now = paymentRepository.findById(stale.getId()).orElse(stale);
        Payment.PaymentStatus current = now.getStatus();
        if (current == Payment.PaymentStatus.SUCCEEDED || current == Payment.PaymentStatus.COMPLETED_UNCONFIRMED) {
            // Another trigger recorded the same payment first: nothing to add.
            return CheckOutcome.PAID;
        }
        if (current == Payment.PaymentStatus.FAILED || current == Payment.PaymentStatus.EXPIRED) {
            operatorCase(now, "paid_after_close", "Paynow reports " + status.rawStatus()
                    + " on a payment we had closed " + current + " (Paynow ref " + paynowRef
                    + "). The customer paid; confirm the booking by hand or refund.");
            return CheckOutcome.CLOSED;
        }
        log.warn("[paynow] paid status for paymentReference={} left alone: the row is {}",
                now.getPaymentReference(), current);
        return CheckOutcome.OPEN;
    }

    /** A paid status must echo our amount and our reference; absent echoes are not evidence. */
    static String echoMismatch(Payment p, PaynowStatus status) {
        if (status.amount() != null && p.getAmount() != null && status.amount().compareTo(p.getAmount()) != 0) {
            return "amount: ledger " + p.getAmount() + " vs echo " + status.amount();
        }
        if (status.reference() != null && p.getPaymentReference() != null
                && !status.reference().equals(p.getPaymentReference())) {
            return "reference: ledger " + p.getPaymentReference() + " vs echo " + status.reference();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Paynow's status updates (the result URL)
    // ---------------------------------------------------------------------

    /** How to answer Paynow: OK = 200; RETRY = an error status, so Paynow sends the update again (up to ten times). */
    public enum UpdateAck { OK, RETRY }

    /**
     * A status update Paynow posted to our result URL. Only acted on when its
     * hash verifies under the payment's own integration, and even then only as
     * a trigger: the payment is checked by polling.
     */
    public UpdateAck onResultUpdate(String rawBody) {
        PaynowMessage message = PaynowMessage.parse(rawBody);
        String reference = message.text("reference");
        if (reference == null) {
            metrics.incPaynowResultUpdate("ignored");
            log.warn("[paynow-result] update without a reference — ignored");
            return UpdateAck.OK;
        }
        Payment p = paymentRepository.findByPaymentReference(reference).orElse(null);
        if (p == null || p.getPaymentRail() == null || !p.getPaymentRail().isPaynow()) {
            metrics.incPaynowResultUpdate("ignored");
            log.info("[paynow-result] update for no Paynow payment of ours — ignored");
            return UpdateAck.OK;
        }
        PaynowStatus update = paynowClient.verifiedUpdate(p.getCurrency(), message).orElse(null);
        if (update == null) {
            metrics.incPaynowResultUpdate("bad_hash");
            log.warn("[paynow-result] update for paymentReference={} failed its hash check — ignored",
                    p.getPaymentReference());
            return UpdateAck.OK;
        }
        if (p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED) {
            CheckOutcome checked = resolveOpen(p, Trigger.RESULT_UPDATE);
            if (checked == CheckOutcome.ERROR) {
                metrics.incPaynowResultUpdate("retry");
                return UpdateAck.RETRY;
            }
            metrics.incPaynowResultUpdate("applied");
            return UpdateAck.OK;
        }
        return afterTheFact(p, update);
    }

    /**
     * An update on a payment we have already settled one way or the other.
     * Two of them matter, and both are an operator's: money paid on a payment
     * we closed, and money refunded or disputed after the booking was
     * confirmed. Each is confirmed by polling before anyone is told.
     */
    private UpdateAck afterTheFact(Payment p, PaynowStatus update) {
        boolean closedUnpaid = p.getStatus() == Payment.PaymentStatus.FAILED
                || p.getStatus() == Payment.PaymentStatus.EXPIRED;
        boolean paid = p.getStatus() == Payment.PaymentStatus.SUCCEEDED
                || p.getStatus() == Payment.PaymentStatus.COMPLETED_UNCONFIRMED;
        boolean worthChecking = (closedUnpaid && update.outcome() == PaynowStatus.Outcome.PAID)
                || (paid && update.outcome() == PaynowStatus.Outcome.REVERSED);
        if (!worthChecking || p.getPaynowFlaggedAt() != null) {
            metrics.incPaynowResultUpdate("ignored");
            return UpdateAck.OK;
        }
        String pollUrl = p.getPaynowPollUrl() != null ? p.getPaynowPollUrl() : update.pollUrl();
        PaynowStatus confirmed;
        try {
            confirmed = pollUrl != null ? paynowClient.poll(p.getCurrency(), pollUrl)
                    : paynowClient.trace(p.getCurrency(), p.getPaynowTrace());
        } catch (RuntimeException e) {
            metrics.incPaynowResultUpdate("retry");
            log.warn("[paynow-result] could not confirm a late {} for paymentReference={}: {}",
                    update.rawStatus(), p.getPaymentReference(), e.getMessage());
            return UpdateAck.RETRY;
        }
        metrics.incPaynowResultUpdate("applied");
        if (closedUnpaid && confirmed.outcome() == PaynowStatus.Outcome.PAID) {
            String mismatch = echoMismatch(p, confirmed);
            operatorCase(p, "paid_after_close", "Paynow reports " + confirmed.rawStatus()
                    + " on a payment we had closed " + p.getStatus() + " (Paynow ref " + confirmed.paynowReference() + ")"
                    + (mismatch != null ? "; the echo does not match the ledger (" + mismatch + ")" : "")
                    + ". The customer paid; confirm the booking by hand or refund.");
        } else if (paid && confirmed.outcome() == PaynowStatus.Outcome.REVERSED) {
            onReversedAfterPaid(p, confirmed);
        }
        return UpdateAck.OK;
    }

    /**
     * Paynow reports a payment we recorded as paid refunded or disputed. While
     * its booking is still unconfirmed (COMPLETED_UNCONFIRMED), the row is
     * parked IN_DOUBT so the confirm-retry does not issue tickets for money that
     * went back. Once the booking is confirmed, the tickets are the operator's
     * call.
     */
    private void onReversedAfterPaid(Payment p, PaynowStatus confirmed) {
        String paynowRef = confirmed.paynowReference() != null ? confirmed.paynowReference() : p.getPaynowReference();
        if (p.getStatus() == Payment.PaymentStatus.COMPLETED_UNCONFIRMED
                && paymentRecordService.parkUnconfirmedInDoubt(p.getId(), "Paynow reports " + confirmed.rawStatus()
                        + " on a payment whose booking we never confirmed (Paynow ref " + paynowRef
                        + "); parked IN_DOUBT so the confirm-retry stops. Operator: check Paynow's records before"
                        + " any confirm or refund.")) {
            operatorCase(p, "reversed_before_paid", "Paynow reports " + confirmed.rawStatus()
                    + " before the booking was confirmed (Paynow ref " + paynowRef + "). Parked IN_DOUBT, so the"
                    + " confirm-retry stops. Check the booking: an earlier confirm that timed out may still have"
                    + " reached it.");
            return;
        }
        operatorCase(p, "reversed_after_paid", "Paynow reports " + confirmed.rawStatus()
                + " after the booking was confirmed (Paynow ref " + paynowRef
                + "). Decide what happens to the tickets.");
    }

    /**
     * Hand a payment to an operator: journal, an ERROR log line and the
     * outcome's counter (alert PaynowPaymentNeedsOperator). Once per payment:
     * a repeated update or a re-check does not raise the same case again.
     */
    private void operatorCase(Payment p, String outcome, String detail) {
        if (!paymentRecordService.flagPaynowOperatorCase(p.getId())) {
            log.info("[paynow] operator case {} already raised for paymentReference={} — not raised again",
                    outcome, p.getPaymentReference());
            return;
        }
        paymentRecordService.noteEvent(p.getId(), detail);
        log.error("[paynow] OPERATOR CASE {} paymentReference={} orderRef={}: {}",
                outcome, p.getPaymentReference(), p.getOrderRef(), detail);
        metrics.incPaynowResolution(outcome);
    }

    // ---------------------------------------------------------------------
    // O'mari: the code the customer types
    // ---------------------------------------------------------------------

    /**
     * What happened to a code.
     *
     * @param payment    the payment, re-read after the attempt (null when there is none)
     * @param httpStatus 200 when the code was taken (the payment says how it stands), else the refusal's status
     * @param message    the refusal, for the customer
     */
    public record OtpOutcome(Payment payment, int httpStatus, String message) {}

    /** Pass the code O'mari texted to the customer on to Paynow. Never retried. */
    public OtpOutcome submitOtp(UUID paymentId, String otp) {
        Payment p = paymentRepository.findById(paymentId).orElse(null);
        if (p == null || p.getPaymentRail() != PaymentRail.OMARI) {
            return new OtpOutcome(null, 404, "Payment not found");
        }
        if (p.getStatus() == Payment.PaymentStatus.SUCCEEDED
                || p.getStatus() == Payment.PaymentStatus.COMPLETED_UNCONFIRMED
                || p.getStatus() == Payment.PaymentStatus.IN_DOUBT) {
            // Paid (or being checked): a repeated or late code changes nothing.
            // Answer with the payment as it stands, never "start a new payment",
            // which invites a second charge. O'mari is not asked again.
            return new OtpOutcome(p, 200, null);
        }
        if (p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED || p.getPaynowOtpUrl() == null
                || (p.getCodeExpiresAt() != null && p.getCodeExpiresAt().isBefore(Instant.now()))) {
            return new OtpOutcome(p, 409, "This payment is no longer waiting for a code — start a new payment");
        }
        String code = otp == null ? "" : otp.strip();
        if (!code.matches("\\d{4,10}")) {
            return new OtpOutcome(p, 400, "Enter the code from the SMS O'mari sent you");
        }
        try {
            paynowClient.submitOtp(p.getCurrency(), p.getPaynowOtpUrl(), code);
        } catch (PaynowApiException e) {
            log.info("[paynow] O'mari code refused paymentReference={} error={}", p.getPaymentReference(), e.paynowError());
            paymentRecordService.noteEvent(p.getId(), "O'mari code refused: "
                    + (e.paynowError() != null ? e.paynowError() : "no reason given"));
            return new OtpOutcome(p, 400,
                    "That code was not accepted. Check the SMS from O'mari and try again.");
        } catch (PaynowApiTransientException e) {
            log.warn("[paynow] O'mari code got no answer paymentReference={}: {}", p.getPaymentReference(), e.getMessage());
            paymentRecordService.noteEvent(p.getId(), "O'mari code sent, no usable answer (" + e.getMessage()
                    + ") — the status check resolves the payment");
            return new OtpOutcome(p, 502, "We could not reach O'mari. Wait a moment, then check your payment.");
        }
        // Accepted: confirm the payment the usual way (a poll), not from the reply.
        resolveOpen(paymentRepository.findById(paymentId).orElse(p), Trigger.OTP);
        return new OtpOutcome(paymentRepository.findById(paymentId).orElse(p), 200, null);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * A card or O'mari start still waiting for Paynow's reply: the row is armed
     * (TOKEN_ISSUED before the call, like OneMoney's) but has no page or code
     * URL yet, and is younger than the longest the start can take. A second
     * tap in that window is answered "in progress", never "expired".
     */
    public boolean startInFlight(Payment p) {
        if (p == null || p.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED || p.getCreatedAt() == null) {
            return false;
        }
        boolean nothingYet = (p.getPaymentRail() == PaymentRail.PAYNOW_CARD && p.getPaynowBrowserUrl() == null)
                || (p.getPaymentRail() == PaymentRail.OMARI && p.getPaynowOtpUrl() == null);
        if (!nothingYet) {
            return false;
        }
        Duration bound = Duration.ofMillis((long) properties.getConnectTimeoutMs() + properties.getReadTimeoutMs())
                .plus(START_REPLY_MARGIN);
        return p.getCreatedAt().plus(bound).isAfter(Instant.now());
    }

    // ---------------------------------------------------------------------
    // The sweep (PaymentResolutionJob.pollPaynowPayments)
    // ---------------------------------------------------------------------

    /**
     * The open Paynow payments the sweep should check now, least recently
     * checked first: one whose initiate reply was lost (no poll URL) for
     * {@link #NO_REPLY_GRACE}, one past its deadline plus grace, and any not
     * checked for {@code paynow.recheck-every} (Paynow's update may have been
     * lost). Filtered in the query, so a page of payments that are not due
     * never hides one that is.
     */
    public List<Payment> openPaymentsDue(Instant now, int limit) {
        return paymentRepository.findPaynowDue(Payment.PaymentStatus.TOKEN_ISSUED, RAILS,
                now.minus(NO_REPLY_GRACE),
                now.minus(EXPIRY_GRACE),
                // Only a row with no deadline uses this; none is written without one.
                now.minus(properties.getCardTtl()).minus(EXPIRY_GRACE),
                now.minus(properties.getRecheckEvery()),
                PageRequest.of(0, limit));
    }

    /**
     * Payments we closed unpaid (EXPIRED) in the last
     * {@code paynow.closed-recheck-for} that are due a look for a late
     * payment: every {@code closed-recheck-first-hour-every} in the first hour
     * after closing, then every {@code closed-recheck-later-every}. One already
     * handed to an operator is never read again.
     */
    public List<Payment> closedPaymentsDue(Instant now, int limit) {
        return paymentRepository.findPaynowClosedDue(Payment.PaymentStatus.EXPIRED, RAILS,
                now.minus(properties.getClosedRecheckFor()),
                now.minus(Duration.ofHours(1)),
                now.minus(properties.getClosedRecheckFirstHourEvery()),
                now.minus(properties.getClosedRecheckLaterEvery()),
                PageRequest.of(0, limit));
    }

    /** How long one sweep run may keep starting checks: the scheduler thread is shared with every other rail's poller. */
    public Duration sweepRunBudget() {
        Duration budget = properties.getSweepRunBudget();
        return budget == null || budget.isNegative() || budget.isZero() ? Duration.ofSeconds(25) : budget;
    }

    /**
     * Look again at a payment we closed unpaid, in case the customer paid
     * after all (a card page can outlive our deadline) and Paynow's update
     * about it was lost. Paid is an operator's case, raised once; nothing
     * changes automatically.
     */
    public CheckOutcome recheckClosed(Payment stale) {
        Payment p = paymentRepository.findById(stale.getId()).orElse(stale);
        if (p.getStatus() != Payment.PaymentStatus.EXPIRED || p.getPaymentRail() == null
                || !p.getPaymentRail().isPaynow() || p.getPaynowFlaggedAt() != null
                || (p.getPaynowPollUrl() == null && p.getPaynowTrace() == null)) {
            return CheckOutcome.CLOSED;
        }
        paymentRecordService.stampPaynowChecked(p.getId());
        PaynowStatus status;
        try {
            status = p.getPaynowPollUrl() != null
                    ? paynowClient.poll(p.getCurrency(), p.getPaynowPollUrl())
                    : paynowClient.trace(p.getCurrency(), p.getPaynowTrace());
        } catch (RuntimeException e) {
            metrics.incPaynowResolution("error");
            log.warn("[paynow] late-payment check unavailable paymentReference={} cause={}",
                    p.getPaymentReference(), e.getMessage());
            return CheckOutcome.ERROR;
        }
        paymentRecordService.recordPaynowSeen(p.getId(), status.paynowReference(), status.pollUrl());
        if (status.outcome() != PaynowStatus.Outcome.PAID) {
            return CheckOutcome.CLOSED;
        }
        String paynowRef = status.paynowReference() != null ? status.paynowReference() : p.getPaynowReference();
        String mismatch = echoMismatch(p, status);
        operatorCase(p, "paid_after_close", "Paynow reports " + status.rawStatus()
                + " on a payment we had closed " + p.getStatus() + " (Paynow ref " + paynowRef + ")"
                + (mismatch != null ? "; the echo does not match the ledger (" + mismatch + ")" : "")
                + ". The customer paid; confirm the booking by hand or refund.");
        return CheckOutcome.CLOSED;
    }

    private boolean pastDeadline(Payment p) {
        Instant deadline = p.getCodeExpiresAt() != null ? p.getCodeExpiresAt()
                : p.getCreatedAt().plus(p.getPaymentRail() == PaymentRail.PAYNOW_CARD
                        ? properties.getCardTtl() : properties.getMobileTtl());
        return Instant.now().isAfter(deadline.plus(EXPIRY_GRACE));
    }

    /**
     * The decline hold, EcoCash's rule on the phone rails: while this order's
     * latest attempt on {@code rail} was declined or cancelled moments ago, a
     * new one is refused with that decline. The website re-sends
     * {@code POST /payments} every few seconds, and without the hold each
     * re-send after the customer cancelled the prompt (or after O'mari voided
     * the code) started a new prompt or texted a new code.
     */
    private void requireNoRecentDecline(PaymentRail rail, OrderType orderType, String orderRef) {
        Payment last = paymentRecordService.latestOnRail(orderType, orderRef, rail).orElse(null);
        Long seconds = declineHoldRemaining(last);
        if (seconds == null) {
            return;
        }
        metrics.incPaynowStart(rail.name(), "decline_held");
        log.info("[paynow] {} start refused: order {} {} was declined moments ago (paymentReference={}); hold {}s more",
                rail, orderType, orderRef, last.getPaymentReference(), seconds);
        throw new PaynowDeclinedException(last, seconds);
    }

    /**
     * Seconds until a new attempt on {@code p}'s rail is accepted, when
     * {@code p} is a OneMoney or O'mari attempt the customer's wallet refused
     * or Paynow reports cancelled, inside {@code paynow.decline-hold}; else
     * null. Only the order's latest attempt on the rail holds it, so the
     * start passes that one.
     */
    public Long declineHoldRemaining(Payment p) {
        Duration hold = properties.getDeclineHold();
        if (p == null || hold == null || hold.isZero() || hold.isNegative()
                || p.getPaymentRail() == null || !PHONE_RAILS.contains(p.getPaymentRail())
                || p.getStatus() != Payment.PaymentStatus.FAILED || p.getCompletedAt() == null
                || !(DECLINED_CODE.equals(p.getUpstreamErrorCode()) || CANCELLED_CODE.equals(p.getUpstreamErrorCode()))) {
            return null;
        }
        Instant now = Instant.now();
        Instant until = p.getCompletedAt().plus(hold);
        if (!now.isBefore(until)) {
            return null;
        }
        return Math.max(1, (Duration.between(now, until).toMillis() + 999) / 1000);
    }

    /**
     * A new OneMoney or O'mari attempt refused because the order's last one on
     * that rail was declined or cancelled moments ago. Carries that attempt, so
     * the answer is the decline itself.
     */
    public static class PaynowDeclinedException extends InvalidPaymentRequestException {
        private final transient Payment declined;
        private final long retryAfterSeconds;

        public PaynowDeclinedException(Payment declined, long retryAfterSeconds) {
            super(message(declined, retryAfterSeconds), 400);
            this.declined = declined;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static String message(Payment declined, long retryAfterSeconds) {
            long minutes = Math.max(1, (retryAfterSeconds + 59) / 60);
            String reason = declineReason(declined);
            String said = methodLabel(declined.getPaymentRail()) + ": "
                    + (reason != null ? sentence(reason) : "the payment didn't go through.");
            return said + " Try again in " + minutes + (minutes == 1 ? " minute" : " minutes")
                    + ", or pay another way.";
        }

        public Payment declined() { return declined; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private void requirePromptRoomFor(PaymentRail rail, String msisdn) {
        int max = properties.getMaxPromptsPerNumber();
        if (max <= 0) {
            return;
        }
        Duration window = properties.getPromptsPerNumberWindow();
        Instant now = Instant.now();
        PaymentRecordService.RecentCharges recent = paymentRecordService.recentCharges(
                PHONE_RAILS, EcocashPaymentService.spellingsOf(msisdn), now.minus(window));
        if (recent.count() < max) {
            return;
        }
        Instant frees = recent.oldest() == null ? now.plus(window) : recent.oldest().plus(window);
        long minutes = Math.max(1, (Duration.between(now, frees).toSeconds() + 59) / 60);
        metrics.incPaynowStart(rail.name(), "number_limited");
        log.warn("[paynow] {} refused: {} requests to {} in the last {} (max {})",
                rail, recent.count(), MsisdnMasking.mask(msisdn), window, max);
        throw new InvalidPaymentRequestException("Too many payment requests to this number. Try again in "
                + minutes + (minutes == 1 ? " minute" : " minutes") + ", or pay another way.", 429);
    }

    /** The number as Paynow's mobile methods take it: {@code 0771234567}. Null when it is not Zimbabwean. */
    static String localPhone(String msisdn) {
        String digits = msisdn == null ? "" : msisdn.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("263")) return "0" + digits.substring(3);
        if (digits.length() == 10 && digits.startsWith("0")) return digits;
        if (digits.length() == 9) return "0" + digits;
        return null;
    }

    /** 32 lower-case hex characters: unique per payment, Paynow's limit for merchanttrace. */
    static String newTrace() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** What the customer sees on Paynow's page. Paynow: no confidential information. */
    static String additionalInfo(OrderType orderType) {
        return orderType == OrderType.BOOKING ? "Ticketize tickets" : "Ticketize order";
    }

    private String returnUrlFor(UUID paymentId) {
        String base = properties.getReturnUrl().strip();
        return base + (base.contains("?") ? "&" : "?") + "transactionId=" + paymentId;
    }

    private static String canonicalCurrency(String c) {
        return c == null ? null : c.strip().toUpperCase(Locale.ROOT);
    }

    private static String sentence(String s) {
        return s.endsWith(".") ? s : s + ".";
    }

    private static UUID asUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
