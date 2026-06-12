package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient.LoyaltyCheckoutException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.dto.ShopCheckoutResponse;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.exception.BadRequestException;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.PaymentRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-facing payment endpoints.
 *
 * <ul>
 *   <li>{@code POST /payments} — ticket-purchase payment, the FE's public
 *       entry. <b>Collects real money</b> via the InnBucks 2D-code rail
 *       (Merchant API): an InnBucks PAYMENT code is issued for the booking's
 *       totalAmount and delivered to the BOOKING's phoneNumber (captured at
 *       booking time — the FE sends only {@code bookingId}, exactly the
 *       historical stub contract); the customer approves it in their own
 *       InnBucks app/USSD and the reconciler's poller confirms the booking.
 *       Request/response shapes stay stub-compatible; {@code PROCESSING} is
 *       the one additive status value and {@code paymentCode} /
 *       {@code paymentCodeExpiresAt} are additive fields. Idempotent replay:
 *       a double-tap on an already-paid/in-flight booking returns that
 *       payment's receipt (with the live code if still awaiting approval),
 *       never an error.</li>
 *   <li>{@code POST /payments/shop-checkout} — pay at a shop with cash,
 *       loyalty points, or a mix. <b>Moves real loyalty points</b> via
 *       loyalty-service (earn on the cash leg, burn on the points leg —
 *       both committed atomically). Wallet operations from
 *       {@link TransfersController} are a separate surface and use real
 *       Oradian-backed money.</li>
 * </ul>
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Customer payment endpoints. POST /payments moves real money via the "
        + "InnBucks Bank API; shop checkout moves real loyalty points.")
public class PaymentController {

    private final BookingServiceClient bookingServiceClient;
    private final LoyaltyServiceClient loyaltyServiceClient;
    private final PaymentMetrics metrics;
    private final InnbucksPaymentService innbucksPaymentService;
    private final PaymentRecordService paymentRecordService;
    private final PaymentRepository paymentRepository;

    @PostMapping
    @Operation(
            summary = "Pay for a ticket booking (InnBucks 2D-code)",
            description = "Public endpoint. Body carries only `bookingId` — amount and currency are read " +
                    "server-side from the booking. An InnBucks PAYMENT code is issued for the booking's " +
                    "`totalAmount`; the customer approves it in their own InnBucks app (Scan-to-Pay or Pay by " +
                    "Code). The normal response is `status=PROCESSING` with `paymentCode`, " +
                    "`paymentCodeExpiresAt` and `paymentQrCode` — the FE renders both the typed code and the " +
                    "InnBucks-rendered QR (base64) on the checkout screen. No out-of-band delivery: the " +
                    "response IS the delivery. Once the customer approves, the poller confirms the booking — " +
                    "poll the booking status for the confirmation number. " +
                    "Replay-safe: paying an already-paid or in-flight booking returns that payment's receipt, " +
                    "including the live code + QR while it's still awaiting approval. If the code expires " +
                    "unpaid, the payment closes and POSTing again issues a fresh code."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Code issued (PROCESSING), or replay of a completed payment (SUCCESS)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class),
                            examples = {
                                    @ExampleObject(name = "Code issued — awaiting customer approval", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Approve the payment in your InnBucks app to complete your booking",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "PROCESSING",
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-06-11T15:48:00",
                                                "paymentCode": "701285660",
                                                "paymentCodeExpiresAt": "2026-06-11T15:58:00",
                                                "paymentQrCode": "iVBORw0KGgoAAAANSUhEUg..."
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Replay after the customer approved", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Payment processed successfully",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "SUCCESS",
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": "INN-20260611-AB12CD",
                                                "processedAt": "2026-06-11T15:52:00",
                                                "paymentCode": null,
                                                "paymentCodeExpiresAt": null,
                                                "paymentQrCode": null
                                              }
                                            }
                                            """)
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation error or booking-service rejected the confirm (e.g. hold expired)",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing bookingId", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "validation failed",
                                              "data": { "bookingId": "bookingId is required" }
                                            }
                                            """),
                                    @ExampleObject(name = "Hold expired", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Seat hold expired",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Booking not found in booking-service",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unknown booking", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Booking not found",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "booking-service unreachable",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Downstream down", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Unable to reach booking-service to confirm the booking",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PaymentResponse>> processPayment(
            @Valid @RequestBody PaymentRequest request
    ) {
        log.info("POST /payments bookingId={}", request.getBookingId());

        // Replay semantics: an already-paid or in-flight booking returns the
        // existing payment's receipt — a double-tap must never error (the old
        // stub re-confirmed idempotently; this is the real-money equivalent).
        var existing = paymentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(
                request.getBookingId(), PaymentRecordService.ACTIVE_OR_SUCCEEDED);
        if (existing.isPresent()) {
            Payment p = existing.get();
            log.info("POST /payments replay bookingId={} existing paymentReference={} status={}",
                    request.getBookingId(), p.getPaymentReference(), p.getStatus());
            return toReplayResponse(p, request);
        }

        // The payer is the booking's phone — captured at booking creation
        // (JWT or guest flow). The FE never supplies payment credentials.
        Map<String, Object> booking;
        try {
            booking = bookingServiceClient.getBooking(request.getBookingId());
        } catch (BookingConfirmationException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            return error(status, e.getMessage());
        }
        String payerMsisdn = asString(booking.get("phoneNumber"));
        if (payerMsisdn == null || payerMsisdn.isBlank()) {
            return error(HttpStatus.BAD_REQUEST,
                    "Booking has no payer phone number — create the booking with a phoneNumber to pay via InnBucks");
        }

        InnbucksPaymentResponse outcome;
        try {
            outcome = innbucksPaymentService.processPayment(
                    request.getBookingId(), payerMsisdn, null);
        } catch (BookingConfirmationException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            return error(status, e.getMessage());
        } catch (InvalidPaymentRequestException e) {
            // 409 (already paying — race with another tap) degrades to replay.
            if (e.getStatusCode() == 409) {
                return paymentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDesc(
                                request.getBookingId(), PaymentRecordService.ACTIVE_OR_SUCCEEDED)
                        .map(p -> toReplayResponse(p, request))
                        .orElseGet(() -> error(HttpStatus.CONFLICT, e.getMessage()));
            }
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.UNPROCESSABLE_ENTITY;
            // Keep the stub's error vocabulary: declines surface as 400 with
            // the human-readable reason (e.g. insufficient balance). Compare
            // by value — Framework 7 renamed 422's constant.
            if (status.value() == 422) status = HttpStatus.BAD_REQUEST;
            return error(status, e.getMessage());
        }

        return toStubShapedResponse(outcome, request);
    }

    /** Map the real-money outcome onto the stub's exact response contract. */
    private ResponseEntity<ApiResult<PaymentResponse>> toStubShapedResponse(
            InnbucksPaymentResponse outcome, PaymentRequest request) {
        PaymentResponse.Status status = switch (outcome.getStatus()) {
            case SUCCESS -> PaymentResponse.Status.SUCCESS;
            case PROCESSING -> PaymentResponse.Status.PROCESSING;
            case FAILED -> PaymentResponse.Status.FAILED;
        };
        if (status == PaymentResponse.Status.FAILED) {
            // Stub error vocabulary: non-200 + message, data null.
            String message = outcome.getUpstreamMessage() != null
                    ? outcome.getUpstreamMessage() : "Payment was rejected";
            return error(HttpStatus.BAD_REQUEST, message);
        }
        PaymentResponse response = PaymentResponse.builder()
                .transactionId(transactionIdFrom(outcome.getPaymentReference()))
                .bookingId(outcome.getBookingId())
                .status(status)
                .amountPaid(outcome.getAmountPaid())
                // Currency is always the booking's — never client-supplied.
                .currency(outcome.getCurrency() != null ? outcome.getCurrency() : "USD")
                .confirmationNumber(outcome.getConfirmationNumber())
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .paymentCode(outcome.getPaymentCode())
                .paymentCodeExpiresAt(outcome.getPaymentCodeExpiresAt())
                .paymentQrCode(outcome.getPaymentQrCode())
                .build();
        String message = status == PaymentResponse.Status.SUCCESS
                ? "Payment processed successfully"
                : (outcome.getUpstreamMessage() != null ? outcome.getUpstreamMessage()
                        : "Payment received; booking confirmation will follow shortly");
        log.info("Payment processed transactionId={} bookingId={} status={} confirmation={} amountPaid={}",
                response.getTransactionId(), response.getBookingId(), status,
                response.getConfirmationNumber(), response.getAmountPaid());
        return ResponseEntity.ok(ApiResult.ok(message, response));
    }

    private ResponseEntity<ApiResult<PaymentResponse>> toReplayResponse(Payment p, PaymentRequest request) {
        PaymentResponse.Status status = switch (p.getStatus()) {
            case SUCCEEDED -> PaymentResponse.Status.SUCCESS;
            default -> PaymentResponse.Status.PROCESSING;
        };
        boolean awaitingApproval = status == PaymentResponse.Status.PROCESSING
                && p.getInnbucksCode() != null;
        PaymentResponse response = PaymentResponse.builder()
                .transactionId(p.getId())
                .bookingId(p.getBookingId())
                .status(status)
                .amountPaid(p.getAmount())
                .currency(p.getCurrency())
                .confirmationNumber(p.getConfirmationNumber())
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                // A replay while the code is still open re-surfaces it +
                // the QR so the customer keeps seeing the same artifacts on
                // the FE — e.g. a page refresh during checkout.
                .paymentCode(awaitingApproval ? p.getInnbucksCode() : null)
                .paymentCodeExpiresAt(awaitingApproval && p.getCodeExpiresAt() != null
                        ? LocalDateTime.ofInstant(p.getCodeExpiresAt(), ZoneOffset.UTC) : null)
                .paymentQrCode(awaitingApproval ? p.getCodeQrBase64() : null)
                .build();
        String message;
        if (status == PaymentResponse.Status.SUCCESS) {
            message = "Payment processed successfully";
        } else if (awaitingApproval) {
            message = "Approve the payment in your InnBucks app — your payment code was sent to your phone";
        } else {
            message = "Payment received; booking confirmation will follow shortly";
        }
        return ResponseEntity.ok(ApiResult.ok(message, response));
    }

    /** Stable receipt id: the UUID inside our TKT-PMT-<uuid> reference. */
    private static UUID transactionIdFrom(String paymentReference) {
        try {
            return UUID.fromString(paymentReference.substring("TKT-PMT-".length()));
        } catch (Exception e) {
            return UUID.randomUUID();
        }
    }

    private static ResponseEntity<ApiResult<PaymentResponse>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(
                ApiResult.<PaymentResponse>builder()
                        .code(status.value() + " " + status.name())
                        .message(message)
                        .data(null)
                        .build());
    }

    @PostMapping("/shop-checkout")
    @Operation(
            summary = "Pay at a shop (cash / points / mixed)",
            description = "Authenticated endpoint. Delegates to loyalty-service's internal shop-checkout to " +
                    "move REAL loyalty points: the cash portion earns points per the merchant's loyalty rules " +
                    "(the existing earn rule + any active campaign multiplier); the points portion is burned " +
                    "from the customer's main wallet. Both legs commit atomically inside loyalty-service. " +
                    "The cash amount itself is not collected here — it is reported informationally (presumably " +
                    "settled at the shop counter). " +
                    "Set the amounts according to `paymentMethod`: CASH → only `cashAmount`; POINTS → only " +
                    "`pointsAmount`; CASH_AND_POINTS → both."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Checkout complete",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ShopCheckoutResponse.class),
                            examples = @ExampleObject(name = "Cash + points", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop checkout processed successfully",
                                      "data": {
                                        "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                        "shopId": "5b1c2d3e-4567-890a-bcde-f01234567890",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "msisdn": "0712345678",
                                        "paymentMethod": "CASH_AND_POINTS",
                                        "cashAmount": 10.00,
                                        "pointsRedeemed": 200.0000,
                                        "pointsEarned": 12.5000,
                                        "processedAt": "2026-05-14T10:30:00",
                                        "reference": "SHOP-7c9e6679-7425-40de-944b-e07fc1f90ae7"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure, amounts inconsistent with paymentMethod, or " +
                            "loyalty-service rejected the call (inactive shop/merchant, insufficient balance, etc.)",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Amounts inconsistent", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "paymentMethod=CASH requires cashAmount > 0 and pointsAmount must be null/zero",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Loyalty rejection", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "merchant is not active; no loyalty operations will run",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Shop not found in loyalty-service",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unknown shop", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "shop not found",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "loyalty-service unreachable",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Loyalty down", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Unable to reach loyalty-service for checkout",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<ShopCheckoutResponse>> shopCheckout(
            @Valid @RequestBody ShopCheckoutRequest request,
            Authentication authentication
    ) {
        // CRITICAL: derive the customer's MSISDN from the authenticated
        // principal, NOT from request.getMsisdn(). The previous version
        // trusted the body field, letting any caller burn any other
        // customer's loyalty points by supplying their phone. JwtFilter
        // pins the JWT's `phoneNumber` claim into authentication.getName().
        // Any body-supplied msisdn is now silently ignored.
        String msisdn = (authentication == null) ? null : authentication.getName();
        if (msisdn == null || msisdn.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Authenticated customer MSISDN not available")
                            .data(null)
                            .build());
        }
        if (request.getMsisdn() != null && !request.getMsisdn().isBlank()
                && !request.getMsisdn().equals(msisdn)) {
            log.warn("Ignoring body-supplied msisdn on /payments/shop-checkout (token wins) " +
                    "shopId={} tokenMsisdn={} bodyMsisdn={}",
                    request.getShopId(),
                    innbucks.paymentservice.util.MsisdnMasking.mask(msisdn),
                    innbucks.paymentservice.util.MsisdnMasking.mask(request.getMsisdn()));
        }

        log.info("POST /payments/shop-checkout shopId={} msisdn={} method={} cash={} points={}",
                request.getShopId(),
                innbucks.paymentservice.util.MsisdnMasking.mask(msisdn),
                request.getPaymentMethod(),
                request.getCashAmount(), request.getPointsAmount());

        String mode = paymentModeTag(request.getPaymentMethod());
        long startNanos = System.nanoTime();

        try {
            validateAmounts(request);
        } catch (BadRequestException e) {
            // validateAmounts now throws the typed BadRequestException
            // (was IllegalArgumentException). We catch it here so the
            // metrics counter still ticks the validation_failed branch —
            // letting the exception propagate to GlobalExceptionHandler
            // would still return 400 but miss the per-mode metric. Same
            // response shape either way; the catch is for instrumentation.
            metrics.incShopCheckout("validation_failed", mode);
            metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return ResponseEntity.badRequest().body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code("400 BAD_REQUEST")
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        // Server-owned reference (per-merchant idempotency on the loyalty PURCHASE
        // row). The FE/POS no longer supplies one — this mirrors the
        // TKT-SMS-<uuid> auto-fill convention used by SmsNotificationClient.
        String reference = "SHOP-" + UUID.randomUUID();

        LoyaltyServiceClient.CheckoutResult result;
        try {
            result = loyaltyServiceClient.shopCheckout(
                    request.getShopId(), msisdn,
                    request.getCashAmount(), request.getPointsAmount(),
                    reference);
        } catch (LoyaltyCheckoutException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            // 503 = loyalty unreachable (network); anything else = loyalty
            // refused the call (bad shop, inactive merchant, insufficient balance).
            String outcome = status == HttpStatus.SERVICE_UNAVAILABLE ? "loyalty_unavailable" : "loyalty_rejected";
            metrics.incShopCheckout(outcome, mode);
            metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            log.warn("Shop checkout failed shopId={} status={} reason={}",
                    request.getShopId(), status.value(), e.getMessage());
            return ResponseEntity.status(status).body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code(status.value() + " " + status.name())
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        ShopCheckoutResponse response = ShopCheckoutResponse.builder()
                .transactionId(UUID.randomUUID())
                .shopId(result.shopId())
                .merchantId(result.merchantId())
                .msisdn(msisdn)
                .paymentMethod(request.getPaymentMethod())
                .cashAmount(result.cashAmount())
                .pointsRedeemed(result.pointsRedeemed())
                .pointsEarned(result.pointsEarned())
                // walletBalanceAfter is intentionally NOT propagated to the response.
                // See the ShopCheckoutResponse field comment for the rationale —
                // keeping the balance off the customer-facing API stops POS systems
                // from printing it on the receipt.
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .reference(reference)
                .build();

        metrics.incShopCheckout("success", mode);
        metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        // Log the post-transaction balance (from the loyalty checkout result,
        // not from the response — the field is intentionally off the response).
        // Server logs are an operational audit trail, not a customer artefact.
        log.info("Shop checkout processed transactionId={} shopId={} reference={} pointsEarned={} pointsRedeemed={} balance={}",
                response.getTransactionId(), response.getShopId(), reference,
                response.getPointsEarned(), response.getPointsRedeemed(),
                result.walletBalanceAfter());
        return ResponseEntity.ok(ApiResult.ok("Shop checkout processed successfully", response));
    }

    private static String paymentModeTag(PaymentMethod method) {
        if (method == null) return "unknown";
        return switch (method) {
            case CASH -> "cash";
            case POINTS -> "points";
            case CASH_AND_POINTS -> "mixed";
        };
    }

    /**
     * Cross-field validation between {@code paymentMethod} and the two amount
     * fields — bean validation can't express this on its own.
     */
    private static void validateAmounts(ShopCheckoutRequest r) {
        boolean cash = r.getCashAmount() != null && r.getCashAmount().signum() > 0;
        boolean points = r.getPointsAmount() != null && r.getPointsAmount().signum() > 0;
        switch (r.getPaymentMethod()) {
            case CASH -> {
                if (!cash || points) {
                    throw new BadRequestException(
                            "paymentMethod=CASH requires cashAmount > 0 and pointsAmount must be null/zero");
                }
            }
            case POINTS -> {
                if (!points || cash) {
                    throw new BadRequestException(
                            "paymentMethod=POINTS requires pointsAmount > 0 and cashAmount must be null/zero");
                }
            }
            case CASH_AND_POINTS -> {
                if (!cash || !points) {
                    throw new BadRequestException(
                            "paymentMethod=CASH_AND_POINTS requires both cashAmount > 0 and pointsAmount > 0");
                }
            }
        }
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
}
