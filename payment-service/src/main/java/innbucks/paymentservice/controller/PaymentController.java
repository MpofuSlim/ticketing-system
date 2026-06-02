package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient.LoyaltyCheckoutException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.dto.ShopCheckoutResponse;
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
 * Dummy payment endpoint. POSTing here flips the matching booking from
 * PENDING to CONFIRMED via booking-service so the booking journey can be
 * completed end-to-end. No real payment processor is involved — every call
 * "succeeds" as long as booking-service accepts the confirm.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Dummy payment processing — confirms bookings without taking real money.")
public class PaymentController {

    private final BookingServiceClient bookingServiceClient;
    private final LoyaltyServiceClient loyaltyServiceClient;
    private final PaymentMetrics metrics;

    @PostMapping
    @Operation(
            summary = "Process payment (dummy)",
            description = "Public endpoint — no Authorization header required. Confirms the booking referenced " +
                    "by `bookingId` by calling booking-service's PATCH /bookings/{id}/confirm. " +
                    "No real charge is made; the receipt's `amountPaid` is read from booking-service's " +
                    "`totalAmount` (the source of truth) — clients do not quote the amount. " +
                    "`currency` (defaults to USD) and `cardLast4` are optional and echoed back on the receipt."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Payment processed and booking confirmed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class),
                            examples = @ExampleObject(name = "Payment success", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Payment processed successfully",
                                      "data": {
                                        "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                        "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "status": "SUCCESS",
                                        "amountPaid": 100.00,
                                        "currency": "USD",
                                        "cardLast4": "4242",
                                        "confirmationNumber": "INN-20260502-AB12CD",
                                        "processedAt": "2026-05-02T15:48:00"
                                      }
                                    }
                                    """)
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

        Map<String, Object> confirmedBooking;
        try {
            confirmedBooking = bookingServiceClient.confirmBooking(request.getBookingId());
        } catch (BookingConfirmationException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            log.warn("Payment failed bookingId={} status={} reason={}",
                    request.getBookingId(), status.value(), e.getMessage());
            // Surface booking-service's reason directly so the caller knows why.
            return ResponseEntity.status(status).body(
                    ApiResult.<PaymentResponse>builder()
                            .code(status.value() + " " + status.name())
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        // Amount paid is ALWAYS the booking's totalAmount (the source of truth
        // computed from real seat prices). The client doesn't quote amounts.
        PaymentResponse response = PaymentResponse.builder()
                .transactionId(UUID.randomUUID())
                .bookingId(request.getBookingId())
                .status(PaymentResponse.Status.SUCCESS)
                .amountPaid(asBigDecimal(confirmedBooking.get("totalAmount")))
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .cardLast4(request.getCardLast4())
                .confirmationNumber(asString(confirmedBooking.get("confirmationNumber")))
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        log.info("Payment processed transactionId={} bookingId={} confirmation={} amountPaid={}",
                response.getTransactionId(), response.getBookingId(),
                response.getConfirmationNumber(), response.getAmountPaid());
        return ResponseEntity.ok(ApiResult.ok("Payment processed successfully", response));
    }

    @PostMapping("/shop-checkout")
    @Operation(
            summary = "Pay at a shop (cash / points / mixed) — dummy",
            description = "Public endpoint. No real money changes hands; the call delegates straight to " +
                    "loyalty-service's internal shop-checkout. " +
                    "The cash portion earns points per the merchant's loyalty rules (the existing earn rule " +
                    "+ any active campaign multiplier). The points portion is burned from the customer's " +
                    "main wallet. Both legs commit atomically inside loyalty-service. " +
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
                                        "walletBalanceAfter": 1812.5000,
                                        "processedAt": "2026-05-14T10:30:00"
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
        } catch (IllegalArgumentException e) {
            metrics.incShopCheckout("validation_failed", mode);
            metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return ResponseEntity.badRequest().body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code("400 BAD_REQUEST")
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        LoyaltyServiceClient.CheckoutResult result;
        try {
            result = loyaltyServiceClient.shopCheckout(
                    request.getShopId(), msisdn,
                    request.getCashAmount(), request.getPointsAmount(),
                    request.getReference());
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
                .walletBalanceAfter(result.walletBalanceAfter())
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        metrics.incShopCheckout("success", mode);
        metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        log.info("Shop checkout processed transactionId={} shopId={} pointsEarned={} pointsRedeemed={} balance={}",
                response.getTransactionId(), response.getShopId(),
                response.getPointsEarned(), response.getPointsRedeemed(),
                response.getWalletBalanceAfter());
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
                    throw new IllegalArgumentException(
                            "paymentMethod=CASH requires cashAmount > 0 and pointsAmount must be null/zero");
                }
            }
            case POINTS -> {
                if (!points || cash) {
                    throw new IllegalArgumentException(
                            "paymentMethod=POINTS requires pointsAmount > 0 and cashAmount must be null/zero");
                }
            }
            case CASH_AND_POINTS -> {
                if (!cash || !points) {
                    throw new IllegalArgumentException(
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
