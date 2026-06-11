package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BankApiClient;
import innbucks.paymentservice.client.BankApiException;
import innbucks.paymentservice.client.BankApiTransientException;
import innbucks.paymentservice.client.BankPaymentCommand;
import innbucks.paymentservice.client.BankPaymentResult;
import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.PaymentOutcome;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates a real InnBucks-backed ticketing payment over the PUBLIC
 * Bank API (the s2s innbucks-core-gateway hop is gone):
 *
 * <pre>
 *   1. Resolve the customer's wallet (Bank API linked-account by MSISDN)
 *   2. Fetch the booking's totalAmount + currency (booking-service GET)
 *   3. Open a PENDING payment ledger row (REQUIRES_NEW commit)
 *   4. POST /bank/api/payment (participantReference = our paymentReference)
 *   5. Classify the bank's response
 *      - COMPLETED  -> confirm the booking, then markSucceeded, return SUCCESS
 *      - COMPLETED but confirm fails -> markCompletedUnconfirmed (money moved;
 *        reconciler retries the confirm), return PROCESSING
 *      - PROCESSING / DUPLICATE_DETECTED -> leave PENDING, return PROCESSING
 *      - UPSTREAM_UNAVAILABLE / null outcome -> markInDoubt, return PROCESSING
 *      - REJECTED_* -> markFailed with veengu's code, return FAILED
 *   6. On a transient exception (gateway/veengu unreachable) -> markInDoubt,
 *      return PROCESSING; reconciler resolves later
 * </pre>
 *
 * <p>Ledger discipline: a timeout or unclassifiable outcome is NEVER recorded
 * as FAILED (money may have moved — IN_DOUBT until the processor is queried),
 * and a confirmed debit is NEVER recorded as FAILED just because the booking
 * confirm failed (COMPLETED_UNCONFIRMED carries the veengu reference as the
 * recovery handle). One active-or-successful payment per booking is enforced
 * by the {@code uq_payment_active_booking} partial index, with a friendly
 * 409 pre-check here.
 *
 * <p>Critical ordering at step 5/COMPLETED: confirmBooking happens BEFORE
 * markSucceeded. If markSucceeded fails (DB blip) we end up with a CONFIRMED
 * booking + a still-PENDING payment row — recoverable by the reconciler.
 *
 * <p>Automated refund (for bookings that stay unconfirmable, e.g. hold
 * expired for good) is still a later PR — the veengu Payout flow. Until
 * then, rows stuck in COMPLETED_UNCONFIRMED past the alert threshold are
 * the operator's queue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InnbucksPaymentService {

    private static final String PAYMENT_REFERENCE_PREFIX = "TKT-PMT-";
    private static final String DEFAULT_CURRENCY = "USD";

    private final PaymentRecordService paymentRecordService;
    private final BankApiClient bankApiClient;
    private final BookingServiceClient bookingServiceClient;

    /** Ticketing merchant's InnBucks account number (payment destination) — per-deployment env var. */
    @Value("${payments.innbucks.merchant-account:}")
    private String merchantAccount;

    public InnbucksPaymentResponse processPayment(UUID bookingId,
                                                  String customerMsisdn,
                                                  String idempotencyKey) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(customerMsisdn, "customerMsisdn");
        if (merchantAccount == null || merchantAccount.isBlank()) {
            throw new IllegalStateException(
                    "payments.innbucks.merchant-account is not configured — refusing to debit without a payee account");
        }

        // ---- Step 0: one active payment per booking ---------------------------
        // Friendly pre-check; the uq_payment_active_booking partial index is
        // the real arbiter under concurrency (the race-loser surfaces below
        // as a DataIntegrityViolation on openPending, mapped to the same 409).
        if (paymentRecordService.hasActiveOrSucceededPayment(bookingId)) {
            throw new InvalidPaymentRequestException(
                    "A payment for this booking is already in progress or completed", 409);
        }

        // ---- Step 1: resolve customer wallet ----------------------------------
        String customerAccount = resolveMainWallet(customerMsisdn);

        // ---- Step 2: fetch booking (amount + currency) ------------------------
        Map<String, Object> booking = bookingServiceClient.getBooking(bookingId);
        BigDecimal amount = asBigDecimal(booking.get("totalAmount"));
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPaymentRequestException(
                    "Booking has no positive totalAmount; cannot debit", 422);
        }
        String currency = asString(booking.get("currency"));
        if (currency == null || currency.isBlank()) currency = DEFAULT_CURRENCY;

        // ---- Step 3: open PENDING ledger row ----------------------------------
        String paymentReference = PAYMENT_REFERENCE_PREFIX + UUID.randomUUID();
        Payment draft = Payment.builder()
                .paymentReference(paymentReference)
                .bookingId(bookingId)
                .customerMsisdn(customerMsisdn)
                .customerAccount(customerAccount)
                .merchantAccount(merchantAccount)
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

        // ---- Step 4: debit via the public Bank API ----------------------------
        BankPaymentCommand command = new BankPaymentCommand(
                amount, currency,
                "Ticketing payment " + paymentReference,
                customerAccount, merchantAccount, paymentReference);

        BankPaymentResult bankResult;
        try {
            bankResult = bankApiClient.pay(command);
        } catch (BankApiTransientException e) {
            // Timeout / 5xx / circuit open AFTER the request may have left us —
            // money MAY have moved. IN_DOUBT, never FAILED; the reconciler
            // resolves via transaction inquiry. FE sees PROCESSING.
            log.warn("[innbucks-payment] bank-api transient for paymentReference={} msisdn={} status={}",
                    paymentReference, MsisdnMasking.mask(customerMsisdn), e.getStatusCode());
            paymentRecordService.markInDoubt(opened.getId(),
                    "Bank API transient failure: HTTP " + e.getStatusCode());
            return buildResponse(opened, Status.PROCESSING, null, null,
                    "InnBucks temporarily unavailable; your payment is still being processed");
        } catch (BankApiException e) {
            // Non-decline 4xx / credential failure — the bank actively refused
            // the REQUEST (no money moved). Close the row; ops bug, not customer.
            log.error("[innbucks-payment] bank-api rejected request paymentReference={} status={}",
                    paymentReference, e.getStatusCode(), e);
            paymentRecordService.markFailed(opened.getId(), "bank_rejected", e.getMessage());
            throw new InvalidPaymentRequestException(
                    "Payment request was rejected by InnBucks", 500);
        }

        // ---- Step 5: classify outcome -----------------------------------------
        return handleOutcome(opened, bankResult, bookingId);
    }

    private InnbucksPaymentResponse handleOutcome(Payment opened,
                                                  BankPaymentResult bankResult,
                                                  UUID bookingId) {
        PaymentOutcome outcome = bankResult.outcome();
        if (outcome == null) {
            // Unclassifiable envelope: money MAY have moved. Park IN_DOUBT
            // with whatever reference we got — that's the recovery handle.
            log.error("[innbucks-payment] bank-api returned null outcome paymentReference={}",
                    opened.getPaymentReference());
            paymentRecordService.markInDoubt(opened.getId(),
                    "Bank API returned null outcome; bankRef=" + bankResult.reference());
            return buildResponse(opened, Status.PROCESSING,
                    bankResult.reference(), null,
                    "Outcome unknown; reconciler will resolve");
        }

        switch (outcome) {
            case COMPLETED -> {
                // Confirm the booking BEFORE markSucceeded — see class javadoc
                // for the ordering rationale. The catch returns early, so the
                // markSucceeded call below only runs when confirmationNumber
                // is definitely assigned.
                String confirmationNumber;
                try {
                    Map<String, Object> confirmed = bookingServiceClient.confirmBooking(bookingId);
                    confirmationNumber = asString(confirmed.get("confirmationNumber"));
                } catch (BookingConfirmationException e) {
                    // Customer was debited but booking confirm failed. Money
                    // MOVED — recording this as FAILED (the old behaviour)
                    // told the ledger no money moved, the one lie a payment
                    // ledger must never contain. COMPLETED_UNCONFIRMED parks
                    // it with the veengu reference as the recovery handle;
                    // the reconciler retries the confirm (booking-side
                    // confirm is an idempotent replay) and promotes to
                    // SUCCEEDED. Sustained presence in this state is a page.
                    log.error("[innbucks-payment] BOOKING CONFIRM FAILED AFTER DEBIT — reconciler will retry " +
                                    "paymentReference={} bankRef={} bookingStatus={} reason={}",
                            opened.getPaymentReference(), bankResult.reference(),
                            e.getStatusCode(), e.getMessage());
                    paymentRecordService.markCompletedUnconfirmed(opened.getId(),
                            bankResult.reference(),
                            "Debit COMPLETED but booking confirm rejected (HTTP "
                                    + e.getStatusCode() + "): " + e.getMessage());
                    // FE-visible status is PROCESSING (an existing value on
                    // this endpoint): truthful — the payment landed and the
                    // booking is being finalised, very likely automatically
                    // on the next reconciler pass.
                    return buildResponse(opened, Status.PROCESSING,
                            bankResult.reference(), "booking_confirm_pending",
                            "Payment received; we're finalising your booking. " +
                                    "Reference " + opened.getPaymentReference() +
                                    " — your confirmation will follow shortly.");
                }
                paymentRecordService.markSucceeded(opened.getId(),
                        bankResult.reference(), confirmationNumber);
                opened.setConfirmationNumber(confirmationNumber);
                return buildResponse(opened, Status.SUCCESS,
                        bankResult.reference(), null, null);
            }
            case PROCESSING, DUPLICATE_DETECTED -> {
                // Leave PENDING: the bank answered 2xx but the body didn't
                // classify (or flagged a duplicate). The reconciler resolves
                // by transaction inquiry on its next pass.
                log.info("[innbucks-payment] {} paymentReference={} bankRef={}",
                        outcome, opened.getPaymentReference(), bankResult.reference());
                return buildResponse(opened, Status.PROCESSING,
                        bankResult.reference(), null,
                        "Payment accepted by InnBucks; awaiting confirmation");
            }
            case UPSTREAM_UNAVAILABLE -> {
                // Defensive: the classifier doesn't emit this today (transport
                // failures throw BankApiTransientException instead), but the
                // discipline is identical — outcome unknown, IN_DOUBT.
                log.warn("[innbucks-payment] upstream unavailable paymentReference={}",
                        opened.getPaymentReference());
                paymentRecordService.markInDoubt(opened.getId(),
                        "Bank reported upstream unavailable");
                return buildResponse(opened, Status.PROCESSING, null, null,
                        "InnBucks temporarily unavailable; your payment is still being processed");
            }
            default -> {
                // All REJECTED_* outcomes -> mark FAILED with the bank's code.
                paymentRecordService.markFailed(opened.getId(),
                        bankResult.code(), bankResult.message());
                return buildResponse(opened, Status.FAILED,
                        null, bankResult.code(),
                        humanMessage(outcome, bankResult.message()));
            }
        }
    }

    private String resolveMainWallet(String customerMsisdn) {
        // Linked-account inquiry on the public Bank API: MSISDN -> wallet
        // account number. Replaces the old Oradian-middleware deposits hop.
        try {
            return bankApiClient.findWalletAccount(customerMsisdn)
                    .orElseThrow(() -> new InvalidPaymentRequestException(
                            "No InnBucks wallet found for this customer", 422));
        } catch (BankApiTransientException e) {
            // Pre-debit lookup failure: nothing has moved; safe to surface a
            // retryable 503 to the FE without touching the ledger.
            throw new InvalidPaymentRequestException(
                    "InnBucks temporarily unavailable; please retry shortly", 503);
        }
    }

    private InnbucksPaymentResponse buildResponse(Payment payment,
                                                  Status status,
                                                  String upstreamReference,
                                                  String upstreamCode,
                                                  String upstreamMessage) {
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
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }

    private static String humanMessage(PaymentOutcome outcome, String upstreamMessage) {
        return switch (outcome) {
            case REJECTED_INSUFFICIENT_FUNDS -> "Insufficient balance in your InnBucks wallet";
            case REJECTED_ACCOUNT_UNAVAILABLE -> "Your InnBucks wallet is currently unavailable; please contact InnBucks support";
            case REJECTED_LIMIT_REACHED -> "Daily transaction limit reached on your InnBucks wallet";
            case REJECTED_CURRENCY -> "Currency not supported on your InnBucks wallet for this payment";
            case REJECTED_NOT_AUTHORIZED -> "Payment was not authorised";
            case REJECTED_VALIDATION -> "Payment request was rejected by InnBucks";
            case REJECTED_OTHER -> upstreamMessage != null ? upstreamMessage : "Payment was rejected by InnBucks";
            default -> upstreamMessage != null ? upstreamMessage : "Payment was rejected";
        };
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
