package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.InnbucksCoreGatewayClient;
import innbucks.paymentservice.client.InnbucksCoreGatewayDebitRequest;
import innbucks.paymentservice.client.InnbucksCoreGatewayException;
import innbucks.paymentservice.client.InnbucksCoreGatewayResponse;
import innbucks.paymentservice.client.InnbucksCoreGatewayTransientException;
import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.client.PaymentOutcome;
import innbucks.paymentservice.dto.DepositAccount;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates a real InnBucks/veengu-backed ticketing payment:
 *
 * <pre>
 *   1. Resolve the customer's main InnBucks wallet (Oradian deposits lookup)
 *   2. Fetch the booking's totalAmount + currency (booking-service GET)
 *   3. Open a PENDING payment ledger row (REQUIRES_NEW commit)
 *   4. POST /payments/debit to innbucks-core-gateway -> veengu
 *   5. Classify the gateway envelope's outcome
 *      - COMPLETED  -> confirm the booking, then markSucceeded, return SUCCESS
 *      - PROCESSING / DUPLICATE_DETECTED -> leave PENDING, return PROCESSING
 *      - REJECTED_* -> markFailed with veengu's code, return FAILED
 *   6. On a transient exception (gateway/veengu unreachable) -> leave PENDING,
 *      return PROCESSING; reconciler resolves later
 * </pre>
 *
 * <p>Critical ordering at step 5/COMPLETED: confirmBooking happens BEFORE
 * markSucceeded. If markSucceeded fails (DB blip) we end up with a CONFIRMED
 * booking + a still-PENDING payment row — recoverable by the reconciler.
 * The alternative (markSucceeded before confirmBooking) risks a SUCCEEDED
 * payment row with an unconfirmed booking, which is worse: the customer is
 * debited but doesn't get the seat, and the reconciler can't infer that the
 * booking still needs confirming from the local row alone.
 *
 * <p>KNOWN GAP (PR 2): if veengu debits but confirmBooking fails for a real
 * reason (booking hold expired between fetch and confirm), we mark the
 * payment FAILED with a {@code booking_confirm_failed} code and ops must
 * refund manually in the veengu admin console. The automated refund flow
 * lands in a later PR. The runbook entry tracks this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InnbucksPaymentService {

    private static final String PAYMENT_REFERENCE_PREFIX = "TKT-PMT-";
    private static final String DEFAULT_CURRENCY = "USD";

    private final PaymentRecordService paymentRecordService;
    private final InnbucksCoreGatewayClient gatewayClient;
    private final OradianMiddlewareClient oradianClient;
    private final BookingServiceClient bookingServiceClient;

    /** Ticketing merchant's veengu wallet account number — per-deployment env var. */
    @Value("${payments.innbucks.merchant-account:}")
    private String merchantAccount;

    /** Optional veengu participantId; some merchant configs require it. */
    @Value("${payments.innbucks.participant-id:}")
    private String participantId;

    public InnbucksPaymentResponse processPayment(UUID bookingId,
                                                  String customerMsisdn,
                                                  String idempotencyKey) {
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(customerMsisdn, "customerMsisdn");
        if (merchantAccount == null || merchantAccount.isBlank()) {
            throw new IllegalStateException(
                    "payments.innbucks.merchant-account is not configured — refusing to debit without a payee account");
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
        Payment opened = paymentRecordService.openPending(draft);

        // ---- Step 4: debit veengu via the gateway ----------------------------
        InnbucksCoreGatewayDebitRequest gatewayRequest = InnbucksCoreGatewayDebitRequest.builder()
                .paymentReference(paymentReference)
                .customerMsisdn(customerMsisdn)
                .customerAccount(customerAccount)
                .merchantAccount(merchantAccount)
                .amount(amount)
                .currency(currency)
                .narration("Ticketing payment " + paymentReference)
                .participantId(blankToNull(participantId))
                .build();

        InnbucksCoreGatewayResponse gatewayResponse;
        try {
            gatewayResponse = gatewayClient.debit(gatewayRequest, idempotencyKey);
        } catch (InnbucksCoreGatewayTransientException e) {
            // Gateway/veengu unreachable. Leave PENDING; reconciler resolves later.
            log.warn("[innbucks-payment] gateway transient for paymentReference={} msisdn={} status={}",
                    paymentReference, MsisdnMasking.mask(customerMsisdn), e.getStatusCode());
            return buildResponse(opened, Status.PROCESSING, null, null,
                    "InnBucks core temporarily unavailable; your payment is still being processed");
        } catch (InnbucksCoreGatewayException e) {
            // 4xx from the gateway — request-shape error. This is our bug.
            // Mark FAILED so the row is closed; surface a generic message.
            log.error("[innbucks-payment] gateway rejected request paymentReference={} status={}",
                    paymentReference, e.getStatusCode(), e);
            paymentRecordService.markFailed(opened.getId(), "gateway_rejected", e.getMessage());
            throw new InvalidPaymentRequestException(
                    "Payment request was rejected by the gateway", 500);
        }

        // ---- Step 5: classify outcome -----------------------------------------
        return handleOutcome(opened, gatewayResponse, bookingId);
    }

    private InnbucksPaymentResponse handleOutcome(Payment opened,
                                                  InnbucksCoreGatewayResponse gatewayResponse,
                                                  UUID bookingId) {
        PaymentOutcome outcome = gatewayResponse.outcome();
        if (outcome == null) {
            log.error("[innbucks-payment] gateway returned null outcome paymentReference={}",
                    opened.getPaymentReference());
            return buildResponse(opened, Status.PROCESSING,
                    gatewayResponse.upstreamReference(), null,
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
                    // Customer was debited but booking confirm failed. PR 2 has
                    // no automated refund — close the row FAILED and page ops.
                    log.error("[innbucks-payment] BOOKING CONFIRM FAILED AFTER DEBIT — manual refund required " +
                                    "paymentReference={} veenguRef={} bookingStatus={} reason={}",
                            opened.getPaymentReference(), gatewayResponse.upstreamReference(),
                            e.getStatusCode(), e.getMessage());
                    paymentRecordService.markFailed(opened.getId(),
                            "booking_confirm_failed",
                            "Customer debited (veenguRef=" + gatewayResponse.upstreamReference()
                                    + ") but booking confirm rejected: " + e.getMessage());
                    return buildResponse(opened, Status.FAILED,
                            gatewayResponse.upstreamReference(), "booking_confirm_failed",
                            "Payment processed but booking could not be confirmed. " +
                                    "Reference " + opened.getPaymentReference() + " — please contact support.");
                }
                paymentRecordService.markSucceeded(opened.getId(),
                        gatewayResponse.upstreamReference(), confirmationNumber);
                opened.setConfirmationNumber(confirmationNumber);
                return buildResponse(opened, Status.SUCCESS,
                        gatewayResponse.upstreamReference(), null, null);
            }
            case PROCESSING, DUPLICATE_DETECTED -> {
                // Leave PENDING. DUPLICATE_DETECTED means the first attempt
                // already landed in veengu; without GET-by-reference on
                // VeenguClient we can't yet query the authoritative state, so
                // reconciler will resolve on the next pass.
                log.info("[innbucks-payment] {} paymentReference={} veenguRef={}",
                        outcome, opened.getPaymentReference(), gatewayResponse.upstreamReference());
                return buildResponse(opened, Status.PROCESSING,
                        gatewayResponse.upstreamReference(), null,
                        "Payment accepted by InnBucks; awaiting confirmation");
            }
            case UPSTREAM_UNAVAILABLE -> {
                // Gateway returned 503 in the envelope (rare — usually the
                // client throws InnbucksCoreGatewayTransientException first).
                log.warn("[innbucks-payment] upstream unavailable paymentReference={}",
                        opened.getPaymentReference());
                return buildResponse(opened, Status.PROCESSING, null, null,
                        "InnBucks core temporarily unavailable; your payment is still being processed");
            }
            default -> {
                // All REJECTED_* outcomes -> mark FAILED with the veengu code.
                paymentRecordService.markFailed(opened.getId(),
                        gatewayResponse.upstreamCode(), gatewayResponse.upstreamMessage());
                return buildResponse(opened, Status.FAILED,
                        null, gatewayResponse.upstreamCode(),
                        humanMessage(outcome, gatewayResponse.upstreamMessage()));
            }
        }
    }

    private String resolveMainWallet(String customerMsisdn) {
        List<DepositAccount> accounts = oradianClient.getDepositsForMsisdn(customerMsisdn);
        if (accounts == null || accounts.isEmpty()) {
            throw new InvalidPaymentRequestException(
                    "No InnBucks wallet found for this customer", 422);
        }
        // Pick the customer's main, active deposit account. The
        // isMainAccount / status flags arrive as strings from Oradian (the
        // middleware preserves the wire shape verbatim) — match on the
        // literal "true" / "Active" the middleware emits.
        return accounts.stream()
                .filter(a -> "true".equalsIgnoreCase(a.getIsMainAccount()))
                .filter(a -> "Active".equalsIgnoreCase(a.getStatus()))
                .map(DepositAccount::getInternalID)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseThrow(() -> new InvalidPaymentRequestException(
                        "No active main InnBucks wallet found for this customer", 422));
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
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
