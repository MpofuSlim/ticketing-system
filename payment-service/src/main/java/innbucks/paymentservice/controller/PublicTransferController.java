package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, JWT-authenticated deposit-transfer endpoint. Lets a logged-in
 * customer move funds out of one of their own Oradian deposit accounts.
 *
 * <p>Auth model is bespoke and intentional. payment-service has no Spring
 * Security filter chain (see {@link innbucks.paymentservice.exception.GlobalExceptionHandler}'s
 * comment) — the rest of the service is unauthenticated dummy endpoints
 * (PaymentController) or X-Internal-Token-gated S2S endpoints
 * ({@link InternalTransferController}). This controller is the one exception:
 * it inlines bearer-JWT verification and an Oradian-backed ownership check
 * so the frontend can call it directly without exposing the S2S shared
 * secret. The signing secret is {@code jwt.secret} and MUST match
 * user-service's {@code jwt.secret} exactly.
 *
 * <p>Ownership check: the JWT's {@code phoneNumber} claim is used to look up
 * the caller's Oradian deposit accounts; {@code fromAccountId} from the body
 * must be present in that list. This is the same upstream as user-service's
 * {@code GET /auth/customer/deposits} so the two views stay consistent —
 * accounts visible there are exactly the accounts the customer can transfer
 * from here.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Public Deposit Transfer",
     description = "JWT-authenticated deposit-account transfer. The customer can only move money out of accounts they own.")
public class PublicTransferController {

    private final JwtUtil jwtUtil;
    private final OradianMiddlewareClient oradianMiddlewareClient;

    @PostMapping("/deposit")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Submit a deposit account transfer (authenticated)",
            description = """
                    Submits a money transfer between two Oradian deposit accounts on
                    behalf of the authenticated customer. The source account
                    (`fromAccountId`) MUST belong to the JWT's `phoneNumber`
                    claim — payment-service looks up the caller's deposits via
                    Oradian middleware and rejects the call with 403 if the
                    source account isn't in that list.

                    The accepted token is the standard customer access token
                    minted by user-service (same one used for
                    `/auth/customer/deposits`). Send `Idempotency-Key` to
                    deduplicate retries — payment-service caches the response
                    for 24h per (method, path, key).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transfer submitted",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DepositTransferResponse.class),
                            examples = @ExampleObject(name = "Success", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Deposit transfer submitted",
                                      "data": {
                                        "fromAccountId": "A000001",
                                        "toAccountId": "A000002",
                                        "amount": "123.00",
                                        "paymentAmount": "",
                                        "paymentCurrency": "",
                                        "customExchangeRate": "",
                                        "notes": "Lunch",
                                        "referenceNumber": "1234567980123",
                                        "transactionDate": "2026-05-18",
                                        "transactionID": "1155",
                                        "accountVersion": "",
                                        "customFields": ""
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure (missing fromAccountId/toAccountId/amount/transactionDate)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing field", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "validation failed",
                                      "data": { "amount": "amount is required" }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token, or token has no phoneNumber claim",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing bearer", value = """
                                            {
                                              "code": "401 UNAUTHORIZED",
                                              "message": "Missing Bearer token",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Invalid token", value = """
                                            {
                                              "code": "401 UNAUTHORIZED",
                                              "message": "Invalid or expired bearer token",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "No phoneNumber claim", value = """
                                            {
                                              "code": "401 UNAUTHORIZED",
                                              "message": "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/deposit",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "fromAccountId does not belong to the authenticated customer",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not your account", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "fromAccountId does not belong to the authenticated customer",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "Oradian middleware unreachable or upstream Oradian failed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Upstream down", value = """
                                    {
                                      "code": "502 BAD_GATEWAY",
                                      "message": "Unable to reach Oradian middleware: connect timed out",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<DepositTransferResponse>> transferDeposit(
            HttpServletRequest httpRequest,
            @Valid @RequestBody DepositTransferRequest request) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Bearer token missing on POST /payments/deposit");
            return unauthorized("Missing Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("Invalid bearer token on POST /payments/deposit");
            return unauthorized("Invalid or expired bearer token");
        }
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null) {
            log.warn("Bearer token has no phoneNumber claim on POST /payments/deposit");
            return unauthorized(
                    "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/deposit");
        }

        // Ownership check: source account MUST be one of this customer's
        // Oradian deposit accounts. We look these up by phone via the same
        // upstream as user-service's /auth/customer/deposits — so the
        // accounts shown to the user are exactly the accounts they can
        // transfer from here.
        List<DepositAccount> deposits = oradianMiddlewareClient.getDepositsForMsisdn(phoneNumber);
        boolean ownsSource = deposits.stream()
                .map(DepositAccount::getID)
                .filter(id -> id != null && !id.isBlank())
                .anyMatch(id -> id.equals(request.getFromAccountId()));
        if (!ownsSource) {
            log.warn("Ownership rejection on POST /payments/deposit phone={} from={}",
                    phoneNumber, request.getFromAccountId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResult.<DepositTransferResponse>builder()
                            .code("403 FORBIDDEN")
                            .message("fromAccountId does not belong to the authenticated customer")
                            .data(null)
                            .build());
        }

        log.info("POST /payments/deposit phone={} from={} to={} amount={} txnDate={}",
                phoneNumber, request.getFromAccountId(), request.getToAccountId(),
                request.getAmount(), request.getTransactionDate());

        DepositTransferResponse response =
                oradianMiddlewareClient.submitDepositTransfer(request);
        return ResponseEntity.ok(ApiResult.ok("Deposit transfer submitted", response));
    }

    private static ResponseEntity<ApiResult<DepositTransferResponse>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.<DepositTransferResponse>builder()
                        .code("401 UNAUTHORIZED")
                        .message(message)
                        .data(null)
                        .build());
    }
}
