package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.CodeGenerationResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiException;
import innbucks.paymentservice.client.InnbucksApiTransientException;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates an InnBucks <b>2D-code</b> ticket payment — the rail the
 * InnBucks team designated as the primary (and only) collection method:
 *
 * <pre>
 *   1. Fetch the booking's totalAmount + currency (booking-service GET)
 *   2. Open a PENDING payment ledger row (REQUIRES_NEW commit)
 *   3. POST /api/code/generate (type=PAYMENT, amount in CENTS,
 *      reference = our paymentReference)
 *      - approved        -> TOKEN_ISSUED (code + authNumber + local expiry)
 *      - refused         -> FAILED (no money moves on generate), FE sees the reason
 *      - amount echo != sent -> FAILED amount_mismatch (the 100x guard) — the
 *        code is NOT delivered to the customer
 *      - transient/5xx   -> FAILED + 503 (safe: an orphaned upstream code is
 *        unreachable and simply expires; the slot frees for a clean retry)
 *   4. Return PROCESSING with the code + QR echoed on the response — the FE
 *      renders both on the checkout screen so the customer approves them in
 *      their own InnBucks app (Scan-to-Pay, Pay by Code, or the deep link).
 *      No out-of-band delivery: the FE is the single source of truth for
 *      what the customer sees.
 *   5. The reconciler's status poller flips the row when InnBucks reports
 *      Paid (confirm booking -> SUCCEEDED) or Expired/Timed Out (-> EXPIRED,
 *      slot freed).
 * </pre>
 *
 * <p>Ledger discipline carried over from the hardening pass: the PENDING row
 * commits before any upstream call; every transition is journaled; one
 * active-or-successful payment per booking is enforced by the
 * {@code uq_payment_active_booking} partial index (friendly 409 pre-check
 * here, index as the arbiter under races). Unlike the old server-side debit,
 * code <i>generation</i> moves no money — so generation failures may close
 * the row FAILED outright, and IN_DOUBT never arises in this flow. The
 * "money moved but unconfirmed" state still exists downstream: the POLLER
 * records COMPLETED_UNCONFIRMED when a paid code's booking confirm fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InnbucksPaymentService {

    private static final String PAYMENT_REFERENCE_PREFIX = "TKT-PMT-";
    private static final String DEFAULT_CURRENCY = "USD";

    private final PaymentRecordService paymentRecordService;
    private final InnbucksApiClient innbucksApiClient;
    private final BookingServiceClient bookingServiceClient;

    /**
     * How long the customer has to approve the code. Must stay comfortably
     * UNDER the booking hold window so a paid code can still confirm its
     * booking; the InnBucks-side validity (~10 min per the doc's samples)
     * is the upper bound.
     */
    @Value("${payments.innbucks.code.ttl:PT10M}")
    private Duration codeTtl;

    public InnbucksPaymentResponse processPayment(UUID bookingId,
                                                  String customerMsisdn,
                                                  String idempotencyKey) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(customerMsisdn, "customerMsisdn");

        // ---- Step 0: one active payment per booking ---------------------------
        // Friendly pre-check; the uq_payment_active_booking partial index is
        // the real arbiter under concurrency (the race-loser surfaces below
        // as a DataIntegrityViolation on openPending, mapped to the same 409).
        if (paymentRecordService.hasActiveOrSucceededPayment(bookingId)) {
            throw new InvalidPaymentRequestException(
                    "A payment for this booking is already in progress or completed", 409);
        }

        // ---- Step 1: fetch booking (amount + currency) ------------------------
        Map<String, Object> booking = bookingServiceClient.getBooking(bookingId);
        BigDecimal amount = asBigDecimal(booking.get("totalAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentRequestException(
                    "Booking has no positive totalAmount; cannot request payment", 422);
        }
        String currency = asString(booking.get("currency"));
        if (currency == null || currency.isBlank()) currency = DEFAULT_CURRENCY;
        long amountCents = toCents(amount);

        // ---- Step 2: open PENDING ledger row ----------------------------------
        String paymentReference = PAYMENT_REFERENCE_PREFIX + UUID.randomUUID();
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .bookingId(bookingId)
                .customerMsisdn(customerMsisdn)
                .amount(amount)
                .currency(currency)
                .idempotencyKey(idempotencyKey)
                .build();
        Payment opened;
        try {
            opened = paymentRecordService.openPending(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race loser against uq_payment_active_booking (two concurrent
            // submits for the same booking): the other request's row stands.
            log.warn("[innbucks-payment] concurrent payment refused by active-booking index bookingId={}",
                    bookingId);
            throw new InvalidPaymentRequestException(
                    "A payment for this booking is already in progress or completed", 409);
        }

        // ---- Step 3: mint the InnBucks PAYMENT code ----------------------------
        CodeGenerationResult generated;
        try {
            generated = innbucksApiClient.generatePaymentCode(
                    paymentReference, "InnBucks ticket booking " + shortId(bookingId), amountCents);
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

        log.info("[innbucks-payment] code issued paymentReference={} msisdn={} authNumber={} expiresAt={}",
                paymentReference, MsisdnMasking.mask(customerMsisdn), generated.authNumber(), expiresAt);
        return buildResponse(opened, Status.PROCESSING,
                generated.authNumber(), null,
                "Approve the payment in your InnBucks app to complete your booking",
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

    /**
     * Booking totals are decimal major units (e.g. 50.00 USD); the Merchant
     * API takes CENTS. Anything with sub-cent precision is refused rather
     * than silently rounded — a ledger must never charge an amount that
     * differs from the booking by even a fraction.
     */
    static long toCents(BigDecimal amount) {
        try {
            return amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidPaymentRequestException(
                    "Booking totalAmount has sub-cent precision; cannot request payment", 422);
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Thrown by the service for validation failures the controller maps to 4xx. */
    public static class InvalidPaymentRequestException extends RuntimeException {
        private final int statusCode;
        public InvalidPaymentRequestException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
