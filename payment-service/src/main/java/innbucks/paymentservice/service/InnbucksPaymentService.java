package innbucks.paymentservice.service;

import innbucks.paymentservice.client.CodeGenerationResult;
import innbucks.paymentservice.client.CodeStatusResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiException;
import innbucks.paymentservice.client.InnbucksApiTransientException;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.order.OrderGateway;
import innbucks.paymentservice.order.OrderGatewayRegistry;
import innbucks.paymentservice.order.OrderSnapshot;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates an InnBucks <b>2D-code</b> checkout payment — the rail the
 * InnBucks team designated as the primary (and only) collection method. It
 * serves ANY product wired behind an {@link OrderGateway} (ticket bookings,
 * marketplace orders, ...); everything product-specific — where the amount
 * comes from, unit conversion, settlement tagging, hold extension, confirm —
 * lives in the gateway selected by {@link OrderType}:
 *
 * <pre>
 *   1. gateway.fetch(orderRef): amount (ALREADY in cents — the gateway is the
 *      product's single major↔minor conversion point), currency, payer
 *      msisdn, settlement tag + narration, payable flag
 *   2. gateway.extendHold(orderRef): the product-side hold provably outlives
 *      the code (TTL + safety margin) BEFORE any ledger write or mint
 *   3. Open a PENDING payment ledger row (REQUIRES_NEW commit)
 *   4. POST /api/code/generate (type=PAYMENT, amount in CENTS,
 *      reference = our paymentReference)
 *      - approved        -> TOKEN_ISSUED (code + authNumber + local expiry)
 *      - refused         -> FAILED (no money moves on generate), FE sees the reason
 *      - amount echo != sent -> FAILED amount_mismatch (the 100x guard) — the
 *        code is NOT delivered to the customer
 *      - transient/5xx   -> FAILED + 503 (safe: an orphaned upstream code is
 *        unreachable and simply expires; the slot frees for a clean retry)
 *   5. Return PROCESSING with the code + QR echoed on the response — the FE
 *      renders both on the checkout screen so the customer approves them in
 *      their own InnBucks app. No out-of-band delivery: the FE is the single
 *      source of truth for what the customer sees.
 *   6. The reconciler's status poller flips the row when InnBucks reports
 *      Paid (confirm the order via its gateway -> SUCCEEDED) or Expired/Timed
 *      Out (-> EXPIRED, slot freed).
 * </pre>
 *
 * <p>Ledger discipline carried over from the hardening pass: the PENDING row
 * commits before any upstream call; every transition is journaled; one
 * active-or-successful payment per (order_type, order_ref) is enforced by the
 * {@code uq_payment_active_order} partial index (friendly 409 pre-check
 * here, index as the arbiter under races). Code <i>generation</i> moves no
 * money — so generation failures may close the row FAILED outright, and
 * IN_DOUBT never arises in this flow. The "money moved but unconfirmed"
 * state still exists downstream: the POLLER records COMPLETED_UNCONFIRMED
 * when a paid code's order confirm fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InnbucksPaymentService {

    private final PaymentRecordService paymentRecordService;
    private final InnbucksApiClient innbucksApiClient;
    private final OrderGatewayRegistry orderGatewayRegistry;
    private final CodePaymentResolutionService codePaymentResolutionService;

    /**
     * How long the customer has to approve the code. Must stay comfortably
     * UNDER the product hold window (each gateway extends its hold to TTL +
     * {@link OrderGateway#HOLD_SAFETY_MARGIN}) so a paid code can still
     * confirm its order; the InnBucks-side validity (~10 min per the doc's
     * samples) is the upper bound.
     */
    @Value("${payments.innbucks.code.ttl:PT10M}")
    private Duration codeTtl;

    /**
     * This deployment's currency (ZW cell = USD, KE cell = KES, ...).
     * Defensive fallback when a product snapshot carries no currency —
     * bookings are single-country per cell with no currency column, so their
     * gateway already applies this; kept here as the last line.
     */
    @Value("${innbucks.currency:USD}")
    private String cellCurrency;

    /**
     * Run a 2D-code payment for any product order.
     *
     * @param orderType           selects the {@link OrderGateway}
     * @param orderRef            the product-side reference (booking UUID
     *                            text / MKT-... order ref)
     * @param payerMsisdnOverride the authenticated caller's MSISDN when the
     *                            entry point has one (the JWT variant) —
     *                            wins over the snapshot's payer; null on the
     *                            public guest entry, where the order's own
     *                            payer contact is used
     * @param idempotencyKey      the Idempotency-Key header value, when the
     *                            entry point requires one
     */
    public InnbucksPaymentResponse processPayment(OrderType orderType,
                                                  String orderRef,
                                                  String payerMsisdnOverride,
                                                  String idempotencyKey) {
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(orderRef, "orderRef");
        OrderGateway gateway = orderGatewayRegistry.forType(orderType);
        String noun = productNoun(orderType);

        // ---- Step 0: one active payment per order -----------------------------
        // Friendly pre-check; the uq_payment_active_order partial index is
        // the real arbiter under concurrency (the race-loser surfaces below
        // as a DataIntegrityViolation on openPending, mapped to the same 409).
        if (paymentRecordService.hasActiveOrSucceededPayment(orderType, orderRef)) {
            throw new InvalidPaymentRequestException(
                    "A payment for this " + noun + " is already in progress or completed", 409);
        }

        // ---- Step 1: fetch the order through its gateway ----------------------
        // Amount arrives ALREADY in cents — the gateway is the single
        // major<->minor conversion point for its product (booking dollars
        // convert there; marketplace totals are cents natively).
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

        // The payer is the order's phone (captured at order creation) unless
        // the entry point authenticated one (JWT). The FE never supplies
        // payment credentials.
        String customerMsisdn = payerMsisdnOverride != null && !payerMsisdnOverride.isBlank()
                ? payerMsisdnOverride : snapshot.payerMsisdn();
        if (customerMsisdn == null || customerMsisdn.isBlank()) {
            throw new InvalidPaymentRequestException(
                    capitalize(noun) + " has no payer phone number — create the " + noun
                            + " with a phone number to pay via InnBucks", 400);
        }

        // ---- Step 1.5: make the product hold outlive the code -----------------
        // Extend BEFORE any ledger write or code mint; a refusal means the
        // order is already expired/cancelled, so the customer re-orders with
        // ZERO money moved. The gateway throws the customer-facing refusal.
        gateway.extendHold(orderRef);

        // ---- Step 2: open PENDING ledger row ----------------------------------
        String paymentReference = SettlementReference.forTag(snapshot.settlementTag());
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .orderType(orderType)
                .orderRef(orderRef)
                .bookingId(orderType == OrderType.BOOKING ? asUuid(orderRef) : null)
                .customerMsisdn(customerMsisdn)
                .amount(BigDecimal.valueOf(amountCents, 2))
                .currency(currency)
                .idempotencyKey(idempotencyKey)
                .build();
        Payment opened;
        try {
            opened = paymentRecordService.openPending(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race loser against uq_payment_active_order (two concurrent
            // submits for the same order): the other request's row stands.
            log.warn("[innbucks-payment] concurrent payment refused by active-order index orderType={} orderRef={}",
                    orderType, orderRef);
            throw new InvalidPaymentRequestException(
                    "A payment for this " + noun + " is already in progress or completed", 409);
        }

        // ---- Step 3: mint the InnBucks PAYMENT code ----------------------------
        CodeGenerationResult generated;
        try {
            generated = innbucksApiClient.generatePaymentCode(
                    paymentReference,
                    snapshot.narration(),
                    amountCents);
        } catch (InnbucksApiTransientException e) {
            // Generation moves NO money. Worst case a code was minted upstream
            // that nobody will ever see — it expires on its own. Closing the
            // row FAILED is therefore safe AND frees the slot for a clean
            // retry; deliberately never retried here (a retry could mint a
            // second live code and split our tracking from the one paid).
            log.warn("[innbucks-payment] code generation transient paymentReference={} msisdn={} status={}",
                    paymentReference, MsisdnMasking.mask(customerMsisdn), e.getStatusCode());
            paymentRecordService.markFailed(opened.getId(), "innbucks_unreachable",
                    "Code generation transient failure: " + e.getMessage());
            throw new InvalidPaymentRequestException(
                    "InnBucks is temporarily unavailable; please try again shortly", 503);
        } catch (InnbucksApiException e) {
            // Credentials / request-shape refusal — ops bug, not customer.
            log.error("[innbucks-payment] code generation rejected paymentReference={} status={}",
                    paymentReference, e.getStatusCode(), e);
            paymentRecordService.markFailed(opened.getId(), "innbucks_rejected", e.getMessage());
            throw new InvalidPaymentRequestException(
                    "Payment request was rejected by InnBucks", 500);
        }

        if (!generated.approved()) {
            paymentRecordService.markFailed(opened.getId(),
                    generated.responseCode() != null ? generated.responseCode() : "code_refused",
                    generated.responseMsg());
            return buildResponse(opened, Status.FAILED, null,
                    generated.responseCode(),
                    generated.responseMsg() != null ? generated.responseMsg()
                            : "InnBucks could not start this payment; please try again",
                    null, null, null);
        }

        // The cents/dollars 100x guard: if the platform echoes a different
        // amount than we asked for, the live code is for the WRONG amount.
        // Fail the row and — critically — never deliver the code; it expires
        // unclaimable upstream within minutes.
        if (generated.amountEchoCents() != null && generated.amountEchoCents() != amountCents) {
            log.error("[innbucks-payment] AMOUNT MISMATCH on code generation — sent {} cents, "
                            + "InnBucks echoed {} — check the amount-unit contract! paymentReference={}",
                    amountCents, generated.amountEchoCents(), paymentReference);
            paymentRecordService.markFailed(opened.getId(), "amount_mismatch",
                    "Sent " + amountCents + " cents but InnBucks echoed " + generated.amountEchoCents());
            return buildResponse(opened, Status.FAILED, null, "amount_mismatch",
                    "Could not start the payment — please try again or contact support",
                    null, null, null);
        }

        // ---- Step 4: record TOKEN_ISSUED; the code rides back on the response -
        // No out-of-band delivery: the FE renders the code + QR + countdown
        // from the response, so the customer sees them on the same screen
        // they tapped Pay from. A missing notification can never lose a code.
        Instant expiresAt = Instant.now().plus(codeTtl);
        paymentRecordService.markTokenIssued(opened.getId(),
                generated.code(), generated.authNumber(), generated.qrCodeBase64(), expiresAt);

        log.info("[innbucks-payment] code issued paymentReference={} orderType={} msisdn={} authNumber={} expiresAt={}",
                paymentReference, orderType, MsisdnMasking.mask(customerMsisdn),
                generated.authNumber(), expiresAt);
        return buildResponse(opened, Status.PROCESSING,
                generated.authNumber(), null,
                "Approve the payment in your InnBucks app to complete your " + noun,
                generated.code(), generated.qrCodeBase64(), expiresAt);
    }

    private InnbucksPaymentResponse buildResponse(Payment payment,
                                                  Status status,
                                                  String upstreamReference,
                                                  String upstreamCode,
                                                  String upstreamMessage,
                                                  String paymentCode,
                                                  String paymentQrCode,
                                                  Instant codeExpiresAt) {
        return InnbucksPaymentResponse.builder()
                .paymentReference(payment.getPaymentReference())
                .orderType(payment.getOrderType())
                .orderRef(payment.getOrderRef())
                .bookingId(payment.getBookingId())
                .status(status)
                .amountPaid(payment.getAmount())
                .currency(payment.getCurrency())
                .confirmationNumber(payment.getConfirmationNumber())
                .upstreamReference(upstreamReference)
                .upstreamCode(upstreamCode)
                .upstreamMessage(upstreamMessage)
                .paymentCode(paymentCode)
                .paymentQrCode(paymentQrCode)
                .paymentCodeExpiresAt(codeExpiresAt == null ? null
                        : LocalDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    /** Customer-facing word for the product ("booking" / "order"). */
    private static String productNoun(OrderType orderType) {
        return orderType == OrderType.BOOKING ? "booking" : "order";
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static UUID asUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // The booking gateway already refused a non-UUID ref in fetch();
            // defensive only.
            return null;
        }
    }

    /** Outcome of a customer-triggered instant status check on an open code. */
    public enum InstantCheckOutcome { PAID, EXPIRED, PENDING }

    /**
     * Customer-triggered "I've paid" check: ask InnBucks for the code's state
     * RIGHT NOW instead of waiting for the 20s poller. Used by the
     * POST /payments replay path. Resolution goes through the same
     * {@link CodePaymentResolutionService} as the poller. Conservative on
     * every failure: any error/unknown/transient answer leaves the row alone
     * and reports PENDING — the poller remains the authority.
     */
    public InstantCheckOutcome tryResolveOpenCode(Payment payment) {
        if (payment.getStatus() != Payment.PaymentStatus.TOKEN_ISSUED
                || payment.getInnbucksCode() == null || payment.getInnbucksCode().isBlank()) {
            return InstantCheckOutcome.PENDING;
        }
        CodeStatusResult result;
        try {
            result = innbucksApiClient.inquireCodeStatus(payment.getInnbucksCode());
        } catch (RuntimeException e) {
            log.debug("[innbucks-payment] instant check unavailable paymentReference={} cause={}",
                    payment.getPaymentReference(), e.getMessage());
            return InstantCheckOutcome.PENDING;
        }
        return switch (result.status()) {
            case PAID, CLAIMED -> {
                log.info("[innbucks-payment] instant check: code PAID paymentReference={} — completing now",
                        payment.getPaymentReference());
                codePaymentResolutionService.completePaid(payment, result.rawStatus());
                yield InstantCheckOutcome.PAID;
            }
            case EXPIRED -> {
                codePaymentResolutionService.markExpiredUpstream(payment, result.rawStatus());
                yield InstantCheckOutcome.EXPIRED;
            }
            case TIMED_OUT -> {
                codePaymentResolutionService.markExpiredUpstream(payment, "Timed Out");
                yield InstantCheckOutcome.EXPIRED;
            }
            default -> InstantCheckOutcome.PENDING;
        };
    }

    public static class InvalidPaymentRequestException extends RuntimeException {
        private final int statusCode;
        public InvalidPaymentRequestException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
