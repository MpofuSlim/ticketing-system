package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.InnbucksPaymentRequest;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.idempotency.IdempotencyFilter;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.util.BookingIdMasking;
import innbucks.paymentservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated InnBucks 2D-code payment endpoint. Sits ALONGSIDE the FE's
 * public {@code POST /payments} (same flow, same service underneath) and adds
 * JWT + Idempotency-Key discipline for callers that can supply them — wired
 * up only when {@code payments.innbucks.enabled=true} so it can be toggled
 * per environment.
 *
 * <p>Contract:
 * <ul>
 *   <li>JWT-authenticated (customer MSISDN derived from the token, never
 *       trusted from the body — defence against charging the wrong wallet).</li>
 *   <li>{@code Idempotency-Key} header required (enforced by
 *       {@link IdempotencyFilter#REQUIRED_PATHS} which lists this path).</li>
 *   <li>Body: {@code { bookingId }} only. Amount / currency are derived
 *       server-side from the booking.</li>
 * </ul>
 *
 * <p>HTTP status semantics:
 * <ul>
 *   <li><b>200 OK</b> + status=SUCCESS — debit completed and booking
 *       confirmed.</li>
 *   <li><b>202 Accepted</b> + status=PROCESSING — gateway accepted but the
 *       outcome isn't yet authoritative (PROCESSING / DUPLICATE_DETECTED /
 *       gateway transient). Reconciler will resolve; FE polls booking
 *       status.</li>
 *   <li><b>422 Unprocessable Entity</b> + status=FAILED — terminal rejection
 *       by veengu (insufficient funds, account locked, etc.). The
 *       {@code upstreamCode} field carries the specific reason.</li>
 *   <li><b>400 / 404 / 503</b> — request validation, booking not found,
 *       upstream booking-service unreachable. Mirrors the dummy.</li>
 * </ul>
 */
@RestController
@RequestMapping("/payments")
@ConditionalOnProperty(prefix = "payments.innbucks", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments (InnBucks)", description = "Real InnBucks/veengu-backed payments — feature-flagged.")
public class InnbucksPaymentController {

    private final InnbucksPaymentService paymentService;

    @PostMapping("/innbucks")
    @Operation(
            summary = "Process payment via InnBucks 2D-code (authenticated)",
            description = "Issues an InnBucks PAYMENT code for the booking's totalAmount and delivers it to the " +
                    "authenticated customer's phone (MSISDN derived from the JWT, never from the body); the " +
                    "customer approves it in their own InnBucks app/USSD and the status poller confirms the " +
                    "booking. Normal response is 202 PROCESSING with the `paymentCode` echoed. " +
                    "Requires the `Idempotency-Key` header; duplicate keys with the same body return the cached " +
                    "response, duplicate keys with a different body return 422 idempotency_conflict."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Payment completed and booking confirmed",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = InnbucksPaymentResponse.class),
                            examples = @ExampleObject(name = "Success", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Payment processed successfully",
                                      "data": {
                                        "paymentReference": "TKT-PMT-f0e1d2c3-4567-890a-bcde-f01234567890",
                                        "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "status": "SUCCESS",
                                        "amountPaid": 100.00,
                                        "currency": "USD",
                                        "confirmationNumber": "INN-20260608-AB12CD",
                                        "upstreamReference": "VNG-9af-2026-06-08-001",
                                        "processedAt": "2026-06-08T15:48:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202",
                    description = "Payment accepted by InnBucks, outcome not yet authoritative (poll booking status)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Processing", value = """
                                    {
                                      "code": "202 ACCEPTED",
                                      "message": "Payment accepted by InnBucks; awaiting confirmation",
                                      "data": {
                                        "paymentReference": "TKT-PMT-...",
                                        "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "status": "PROCESSING",
                                        "amountPaid": 100.00,
                                        "currency": "USD",
                                        "processedAt": "2026-06-08T15:48:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "Terminal rejection by veengu (insufficient funds, account locked, etc.)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Insufficient funds", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "Insufficient balance in your InnBucks wallet",
                                      "data": {
                                        "paymentReference": "TKT-PMT-...",
                                        "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "status": "FAILED",
                                        "amountPaid": 100.00,
                                        "currency": "USD",
                                        "upstreamCode": "NOT_SUFFICIENT_FUNDS",
                                        "upstreamMessage": "Customer balance insufficient for transaction",
                                        "processedAt": "2026-06-08T15:48:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Missing or invalid JWT, or token missing phoneNumber claim",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unauthenticated", value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Authenticated customer MSISDN not available",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "InnBucks temporarily unavailable — no code was issued (no money moves on "
                            + "generation), the attempt is closed and the booking can be retried immediately",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Upstream down", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "InnBucks is temporarily unavailable; please try again shortly",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<InnbucksPaymentResponse>> processPayment(
            @Valid @RequestBody InnbucksPaymentRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest,
            @Parameter(hidden = true) Authentication authentication
    ) {
        // Derive MSISDN from JWT — never from the body.
        String msisdn = authentication == null ? null : authentication.getName();
        if (msisdn == null || msisdn.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResult.<InnbucksPaymentResponse>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Authenticated customer MSISDN not available")
                            .data(null)
                            .build());
        }
        String idempotencyKey = httpRequest.getHeader(IdempotencyFilter.HEADER);
        // IdempotencyFilter enforces required-header on this path; defence-in-depth here.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResult.<InnbucksPaymentResponse>builder()
                            .code("400 BAD_REQUEST")
                            .message("Idempotency-Key header is required")
                            .data(null)
                            .build());
        }

        // bookingId is the public-ticket access token; logs use the first-8 mask
        // so a log leak isn't an out-of-band confirmation / ticket dump.
        log.info("POST /payments/innbucks bookingId={} msisdn={} idempotencyKey={}",
                BookingIdMasking.mask(request.getBookingId()), MsisdnMasking.mask(msisdn), idempotencyKey);

        InnbucksPaymentResponse response;
        try {
            response = paymentService.processPayment(request.getBookingId(), msisdn, idempotencyKey);
        } catch (BookingConfirmationException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            log.warn("InnBucks payment booking fetch/confirm failed bookingId={} status={} reason={}",
                    BookingIdMasking.mask(request.getBookingId()), status.value(), e.getMessage());
            return ResponseEntity.status(status).body(
                    ApiResult.<InnbucksPaymentResponse>builder()
                            .code(status.value() + " " + status.name())
                            .message(e.getMessage())
                            .data(null)
                            .build());
        } catch (InvalidPaymentRequestException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.UNPROCESSABLE_ENTITY;
            log.warn("InnBucks payment validation failed bookingId={} reason={}",
                    BookingIdMasking.mask(request.getBookingId()), e.getMessage());
            return ResponseEntity.status(status).body(
                    ApiResult.<InnbucksPaymentResponse>builder()
                            .code(status.value() + " " + status.name())
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        return toResponseEntity(response);
    }

    private static ResponseEntity<ApiResult<InnbucksPaymentResponse>> toResponseEntity(InnbucksPaymentResponse r) {
        HttpStatus status;
        String message;
        switch (r.getStatus()) {
            case SUCCESS -> {
                status = HttpStatus.OK;
                message = "Payment processed successfully";
            }
            case PROCESSING -> {
                status = (r.getUpstreamReference() == null) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.ACCEPTED;
                message = (r.getUpstreamMessage() != null) ? r.getUpstreamMessage() :
                        "Payment accepted by InnBucks; awaiting confirmation";
            }
            case FAILED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                message = (r.getUpstreamMessage() != null) ? r.getUpstreamMessage() : "Payment was rejected";
            }
            default -> {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
                message = "Unknown payment status";
            }
        }
        return ResponseEntity.status(status).body(
                ApiResult.<InnbucksPaymentResponse>builder()
                        .code(status.value() + " " + status.name())
                        .message(message)
                        .data(r)
                        .build());
    }
}
