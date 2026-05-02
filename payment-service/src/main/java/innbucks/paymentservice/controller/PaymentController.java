package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @PostMapping
    @Operation(
            summary = "Process payment (dummy)",
            description = "Confirms the booking referenced by `bookingId` by calling booking-service's " +
                    "PATCH /bookings/{id}/confirm. The Authorization header on this request is forwarded " +
                    "to booking-service, so the same JWT must be valid there. " +
                    "No real charge is made — `amount`, `currency`, `cardLast4` are echoed back on the receipt."
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or booking-service rejected the confirm (e.g. hold expired)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Booking not found in booking-service"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "booking-service unreachable")
    })
    public ResponseEntity<ApiResult<PaymentResponse>> processPayment(
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest httpRequest
    ) {
        log.info("POST /payments bookingId={} amount={} currency={}",
                request.getBookingId(), request.getAmount(), request.getCurrency());

        String authHeader = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        Map<String, Object> confirmedBooking;
        try {
            confirmedBooking = bookingServiceClient.confirmBooking(request.getBookingId(), authHeader);
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

        PaymentResponse response = PaymentResponse.builder()
                .transactionId(UUID.randomUUID())
                .bookingId(request.getBookingId())
                .status(PaymentResponse.Status.SUCCESS)
                // Default to the booking's totalAmount when the caller didn't pass an amount.
                .amountPaid(request.getAmount() != null ? request.getAmount()
                        : asBigDecimal(confirmedBooking.get("totalAmount")))
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .cardLast4(request.getCardLast4())
                .confirmationNumber(asString(confirmedBooking.get("confirmationNumber")))
                .processedAt(LocalDateTime.now())
                .build();

        log.info("Payment processed transactionId={} bookingId={} confirmation={}",
                response.getTransactionId(), response.getBookingId(), response.getConfirmationNumber());
        return ResponseEntity.ok(ApiResult.ok("Payment processed successfully", response));
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
