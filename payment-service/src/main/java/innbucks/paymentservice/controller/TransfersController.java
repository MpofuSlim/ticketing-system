package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.client.OradianMiddlewareException;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import innbucks.paymentservice.dto.WithdrawalRequest;
import innbucks.paymentservice.dto.WithdrawalResponse;
import innbucks.paymentservice.dto.TransactionHistoryResponse;
import innbucks.paymentservice.dto.TransactionView;
import innbucks.paymentservice.entity.Transaction;
import innbucks.paymentservice.entity.TransactionType;
import innbucks.paymentservice.repository.TransactionRepository;
import innbucks.paymentservice.security.JwtUtil;
import innbucks.paymentservice.service.TransactionService;
import innbucks.paymentservice.service.TransferLimitService;
import innbucks.paymentservice.util.MsisdnMasking;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Public, JWT-authenticated money-movement endpoints — currently
 * {@code POST /payments/transfer} (transfer between two Oradian deposit
 * accounts) and {@code POST /payments/withdraw} (record a withdrawal
 * against a deposit account). Both require a customer bearer JWT and
 * both gate on the same Oradian-backed ownership check before forwarding
 * to the middleware.
 *
 * <p>Auth model is bespoke and intentional. payment-service has no Spring
 * Security filter chain (see {@link innbucks.paymentservice.exception.GlobalExceptionHandler}'s
 * comment) — the rest of the service is unauthenticated dummy endpoints
 * (PaymentController). This controller is the one exception: it inlines
 * bearer-JWT verification and an Oradian-backed ownership check so the
 * frontend can call it directly without exposing any shared secret. The
 * signing secret is {@code jwt.secret} and MUST match user-service's
 * {@code jwt.secret} exactly.
 *
 * <p>Ownership check: the JWT's {@code phoneNumber} claim is used to look up
 * the caller's Oradian deposit accounts; the source-account ID from the
 * request body ({@code fromAccountId} for deposit, {@code accountID} for
 * withdraw) must be present in that list. This is the same upstream as
 * user-service's {@code GET /auth/customer/deposits} so the two views stay
 * consistent — accounts visible there are exactly the accounts the customer
 * can operate on here.
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transfers",
     description = "JWT-authenticated deposit-account transfers and withdrawals. " +
             "The customer can only move money out of accounts they own.")
public class TransfersController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    // History endpoint pagination caps. Defaults match Spring Data Page
    // conventions; the max page-size guards against a malicious caller
    // asking for size=1000000 and OOM-ing the response serializer.
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;
    // Default date window when the caller doesn't specify one. Keeps the
    // typical "show me my recent transactions" call cheap.
    private static final int DEFAULT_HISTORY_WINDOW_DAYS = 30;

    private final JwtUtil jwtUtil;
    private final OradianMiddlewareClient oradianMiddlewareClient;
    private final TransactionService transactionService;
    private final TransferLimitService transferLimitService;
    private final TransactionRepository transactionRepository;

    @PostMapping("/transfer")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Submit a deposit-account transfer (authenticated)",
            description = """
                    Submits a money transfer between two Oradian deposit accounts on
                    behalf of the authenticated customer. The source account
                    (`fromAccountId`) MUST belong to the JWT's `phoneNumber`
                    claim — payment-service looks up the caller's deposits via
                    Oradian middleware and rejects the call with 403 if the
                    source account isn't in that list.

                    The accepted token is the standard customer access token
                    minted by user-service (same one used for
                    `/auth/customer/deposits`). The `Idempotency-Key` header
                    is **required** — payment-service caches the 200 response
                    for 24h per (method, path, key) and refuses replays that
                    reuse a key with a different request body (422
                    `idempotency_conflict`). Use a fresh UUID per logical
                    transfer attempt; send the SAME value on every retry of
                    the same attempt.

                    `transactionDate` is stamped by payment-service on
                    receipt — clients do not supply it and any value sent in
                    the body is ignored. The stamped date is echoed back on
                    the response.
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
                    description = "Validation failure (missing fromAccountId/toAccountId/amount), " +
                            "missing Idempotency-Key header, or amount fails BigDecimal parse / sign / scale checks",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing field", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "validation failed",
                                              "data": { "amount": "amount is required" }
                                            }
                                            """),
                                    @ExampleObject(name = "Missing Idempotency-Key", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Idempotency-Key header is required for /payments/transfer",
                                              "data": null,
                                              "errorCode": "idempotency_key_required"
                                            }
                                            """),
                                    @ExampleObject(name = "Non-positive amount", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "amount must be greater than zero",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "Idempotency-Key reused with a different request body. Stripe-style " +
                            "contract — a fresh key must be used for a new logical transfer.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Idempotency conflict", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "Idempotency-Key reused with a different request body — refusing to replay",
                                      "data": null,
                                      "errorCode": "idempotency_conflict"
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
                                              "message": "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/transfer",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Ownership / KYC / account-status gate failed. Three reasons land here: " +
                            "(a) fromAccountId doesn't belong to the authenticated customer, " +
                            "(b) the JWT's tier claim is below 2 (tier-1 customers have no Oradian record), " +
                            "or (c) the source account's Oradian status is not Active (Frozen / Closed / Dormant).",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Not your account", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "fromAccountId does not belong to the authenticated customer",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "KYC tier too low", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "Customer must be at KYC tier 2 or higher to use this endpoint",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Account not Active", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "Source account is not Active (status: Frozen)",
                                              "data": null
                                            }
                                            """)
                            })),
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
    public ResponseEntity<ApiResult<DepositTransferResponse>> transfer(
            HttpServletRequest httpRequest,
            @Valid @RequestBody DepositTransferRequest request) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Bearer token missing on POST /payments/transfer");
            return unauthorized("Missing Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("Invalid bearer token on POST /payments/transfer");
            return unauthorized("Invalid or expired bearer token");
        }
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null) {
            log.warn("Bearer token has no phoneNumber claim on POST /payments/transfer");
            return unauthorized(
                    "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/transfer");
        }

        // KYC tier gate. Customer-facing money endpoints require tier 2 or
        // higher — tier 1 customers (phone + password only, no Oradian
        // Person+Client yet) literally have nothing to transfer from, and
        // staff tokens (no tier claim) shouldn't be hitting these paths
        // at all. Both null and < 2 are 403.
        Integer tier = jwtUtil.extractTier(token);
        if (tier == null || tier < 2) {
            log.warn("Tier gate rejection on POST /payments/transfer phone={} tier={}",
                    MsisdnMasking.mask(phoneNumber), tier);
            return forbidden(
                    "Customer must be at KYC tier 2 or higher to use this endpoint");
        }

        // Ownership check: source account MUST be one of this customer's
        // Oradian deposit accounts. We look these up by phone via the same
        // upstream as user-service's /auth/customer/deposits — so the
        // accounts shown to the user are exactly the accounts they can
        // transfer from here.
        List<DepositAccount> deposits = oradianMiddlewareClient.getDepositsForMsisdn(phoneNumber);
        DepositAccount sourceAccount = deposits.stream()
                .filter(d -> d.getID() != null && !d.getID().isBlank())
                .filter(d -> d.getID().equals(request.getFromAccountId()))
                .findFirst()
                .orElse(null);
        if (sourceAccount == null) {
            log.warn("Ownership rejection on POST /payments/transfer phone={} from={}",
                    MsisdnMasking.mask(phoneNumber), request.getFromAccountId());
            return forbidden("fromAccountId does not belong to the authenticated customer");
        }

        // Account status gate. Frozen / Closed / Dormant accounts must reject
        // before we even attempt to call Oradian — Oradian would refuse with
        // a generic 4xx, but we'd rather surface a precise reason and avoid
        // burning a ledger row + an upstream round-trip on a guaranteed-fail
        // path. Case-insensitive compare because Oradian's wire format isn't
        // consistent on casing.
        if (!"Active".equalsIgnoreCase(sourceAccount.getStatus())) {
            log.warn("Account status gate rejection on POST /payments/transfer phone={} account={} status={}",
                    MsisdnMasking.mask(phoneNumber), request.getFromAccountId(), sourceAccount.getStatus());
            return forbidden(
                    "Source account is not Active (status: " + sourceAccount.getStatus() + ")");
        }

        // Server-stamp the transaction date. transactionDate is JsonIgnore on
        // input so anything the FE sent has already been dropped — overwriting
        // here is the single source of truth for the date that hits Oradian.
        request.setTransactionDate(LocalDate.now());

        // Oradian Instafin's SubmitDepositAccountTransfer marks `notes` as
        // required in its schema, but @JsonInclude(NON_NULL) on our DTO drops
        // the field when the FE omits it — that gets us a generic 422
        // "Request could not be processed" from upstream. Coerce null to ""
        // so we always send the field. An empty string is an acceptable
        // value per the upstream schema's example.
        if (request.getNotes() == null) {
            request.setNotes("");
        }

        BigDecimal parsedAmount = parsePositiveAmount(request.getAmount());

        // Velocity gate: reject above the per-transaction cap, or if this
        // would push today's PENDING + SUCCEEDED total over the per-day
        // cap on the SAME source account. Runs before the ledger write so
        // a hit doesn't litter the table with a FAILED row for every
        // breach — the rejection is policy, not an upstream failure.
        // Throws IllegalArgumentException -> 400 via GlobalExceptionHandler.
        transferLimitService.enforce(sourceAccount.getID(), parsedAmount);

        // Open the local ledger row BEFORE calling Oradian (REQUIRES_NEW
        // transaction so it commits independently). If Oradian succeeds
        // and the markSucceeded write later fails, the row stays PENDING
        // for reconciliation to pick up — much better than the orphan-
        // in-upstream class of bug where the local rolls back silently.
        Transaction ledger = transactionService.openPending(Transaction.builder()
                .transactionType(TransactionType.TRANSFER)
                .customerPhone(phoneNumber)
                .sourceAccountId(request.getFromAccountId())
                .destinationAccountId(request.getToAccountId())
                .amount(parsedAmount)
                .notes(request.getNotes())
                .transactionDate(request.getTransactionDate())
                .idempotencyKey(httpRequest.getHeader(IDEMPOTENCY_HEADER))
                .build());

        log.info("POST /payments/transfer txId={} from={} to={} amount={} txnDate={}",
                ledger.getId(), request.getFromAccountId(), request.getToAccountId(),
                request.getAmount(), request.getTransactionDate());

        DepositTransferResponse response;
        try {
            response = oradianMiddlewareClient.submitDepositTransfer(request);
        } catch (OradianMiddlewareException ex) {
            // Best-effort: log + carry on if markFailed itself throws so the
            // original Oradian failure is what surfaces to the caller. A
            // PENDING row left here is what reconciliation expects to see.
            try {
                transactionService.markFailed(ledger.getId(), ex);
            } catch (Exception fail) {
                log.error("markFailed failed for txId={}; row stays PENDING", ledger.getId(), fail);
            }
            throw ex;
        }

        try {
            transactionService.markSucceeded(ledger.getId(),
                    response.getTransactionID(), response.getReferenceNumber(), null);
        } catch (Exception ex) {
            // Oradian moved the money but the local SUCCEEDED write failed.
            // Surface 200 to the client (the money DID move) and rely on
            // reconciliation to flip the PENDING row — re-throwing here
            // would make the FE think the transfer failed and trigger a
            // retry that would either dedupe via Idempotency-Key OR
            // double-create depending on the FE's behaviour. Logging at
            // ERROR so operators see the gap.
            log.error("Oradian deposit succeeded but markSucceeded failed for txId={} oradianTxn={}",
                    ledger.getId(), response.getTransactionID(), ex);
        }
        return ResponseEntity.ok(ApiResult.ok("Deposit transfer submitted", response));
    }

    @PostMapping("/withdraw")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Submit a withdrawal against a deposit account (authenticated)",
            description = """
                    Records a withdrawal against the authenticated customer's
                    Oradian deposit account, calling Oradian's
                    instafin.EnterWithdrawalOnDepositAccount via the
                    middleware's /internal/transfers/withdraw S2S endpoint.
                    The source account (`accountID`) MUST belong to the JWT's
                    `phoneNumber` claim — payment-service looks up the
                    caller's deposits via Oradian middleware and rejects the
                    call with 403 if the account isn't in that list.

                    Three fields are server-set and cannot be supplied by the
                    client: `transactionDate` is stamped to today,
                    `transactionBranchID` is fixed to `"MobileBanking"`, and
                    `overrideLimitCheck` is fixed to `false` (customer-initiated
                    withdrawals must respect Oradian's product / daily limits).

                    The accepted token is the standard customer access token
                    minted by user-service (same one used for
                    `/auth/customer/deposits` and `/payments/transfer`). The
                    `Idempotency-Key` header is **required** — payment-service
                    caches the 200 response for 24h per (method, path, key)
                    and refuses replays that reuse a key with a different
                    request body (422 `idempotency_conflict`). Use a fresh
                    UUID per logical withdrawal attempt; send the SAME value
                    on every retry of the same attempt.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Withdrawal recorded",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = WithdrawalResponse.class),
                            examples = @ExampleObject(name = "Success", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Withdrawal submitted",
                                      "data": {
                                        "overrideLimitCheck": false,
                                        "accountID": "A000015",
                                        "paymentMethodName": "Cash",
                                        "transactionDate": "2026-05-18",
                                        "amount": "10.00",
                                        "transactionBranchID": "MobileBanking",
                                        "notes": "Cash out at agent",
                                        "referenceNumber": "1234567890123",
                                        "transactionID": "1151",
                                        "commandID": "210"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure (missing accountID/paymentMethodName/amount), " +
                            "missing Idempotency-Key header, or amount fails BigDecimal parse / sign / scale checks",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Missing field", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "validation failed",
                                              "data": { "amount": "amount is required" }
                                            }
                                            """),
                                    @ExampleObject(name = "Missing Idempotency-Key", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Idempotency-Key header is required for /payments/withdraw",
                                              "data": null,
                                              "errorCode": "idempotency_key_required"
                                            }
                                            """),
                                    @ExampleObject(name = "Non-positive amount", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "amount must be greater than zero",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "Idempotency-Key reused with a different request body. Stripe-style " +
                            "contract — a fresh key must be used for a new logical withdrawal.",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Idempotency conflict", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_ENTITY",
                                      "message": "Idempotency-Key reused with a different request body — refusing to replay",
                                      "data": null,
                                      "errorCode": "idempotency_conflict"
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
                                              "message": "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/withdraw",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Ownership / KYC / account-status gate failed. Three reasons land here: " +
                            "(a) accountID doesn't belong to the authenticated customer, " +
                            "(b) the JWT's tier claim is below 2, " +
                            "or (c) the account's Oradian status is not Active.",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Not your account", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "accountID does not belong to the authenticated customer",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "KYC tier too low", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "Customer must be at KYC tier 2 or higher to use this endpoint",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Account not Active", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "Source account is not Active (status: Closed)",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "Oradian middleware unreachable or Oradian rejected the withdrawal " +
                            "(e.g. insufficient balance, account suspended, payment method unknown)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Insufficient funds", value = """
                                    {
                                      "code": "502 BAD_GATEWAY",
                                      "message": "Oradian middleware rejected the withdrawal: Insufficient funds",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<WithdrawalResponse>> withdraw(
            HttpServletRequest httpRequest,
            @Valid @RequestBody WithdrawalRequest request) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Bearer token missing on POST /payments/withdraw");
            return unauthorized("Missing Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            log.warn("Invalid bearer token on POST /payments/withdraw");
            return unauthorized("Invalid or expired bearer token");
        }
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null) {
            log.warn("Bearer token has no phoneNumber claim on POST /payments/withdraw");
            return unauthorized(
                    "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/withdraw");
        }

        // KYC tier gate. See /payments/transfer for the rationale.
        Integer tier = jwtUtil.extractTier(token);
        if (tier == null || tier < 2) {
            log.warn("Tier gate rejection on POST /payments/withdraw phone={} tier={}",
                    MsisdnMasking.mask(phoneNumber), tier);
            return forbidden(
                    "Customer must be at KYC tier 2 or higher to use this endpoint");
        }

        // Ownership check: accountID MUST be one of this customer's Oradian
        // deposit accounts — same upstream as /payments/transfer and
        // /auth/customer/deposits so the view of "your accounts" is consistent.
        List<DepositAccount> deposits = oradianMiddlewareClient.getDepositsForMsisdn(phoneNumber);
        DepositAccount sourceAccount = deposits.stream()
                .filter(d -> d.getID() != null && !d.getID().isBlank())
                .filter(d -> d.getID().equals(request.getAccountID()))
                .findFirst()
                .orElse(null);
        if (sourceAccount == null) {
            log.warn("Ownership rejection on POST /payments/withdraw phone={} account={}",
                    MsisdnMasking.mask(phoneNumber), request.getAccountID());
            return forbidden("accountID does not belong to the authenticated customer");
        }

        // Account status gate. See /payments/transfer for the rationale.
        if (!"Active".equalsIgnoreCase(sourceAccount.getStatus())) {
            log.warn("Account status gate rejection on POST /payments/withdraw phone={} account={} status={}",
                    MsisdnMasking.mask(phoneNumber), request.getAccountID(), sourceAccount.getStatus());
            return forbidden(
                    "Source account is not Active (status: " + sourceAccount.getStatus() + ")");
        }

        // Server-stamp the three fields the FE can't supply. The DTO marks
        // them JsonIgnore on input so anything the FE sent has already been
        // dropped — overwriting here is the single source of truth.
        request.setTransactionDate(LocalDate.now());
        request.setTransactionBranchID("MobileBanking");
        request.setOverrideLimitCheck(false);

        // Oradian's swagger lists `notes` as optional (no required-fields hit
        // observed in practice), but we mirror the deposit flow's defensive
        // null -> "" coercion so a missing `notes` from the FE never lands
        // upstream as a null and surprises the wire format.
        if (request.getNotes() == null) {
            request.setNotes("");
        }

        BigDecimal parsedAmount = parsePositiveAmount(request.getAmount());

        // Same velocity gate as /payments/transfer. See TransferLimitService
        // for the per-tx + per-day rules.
        transferLimitService.enforce(sourceAccount.getID(), parsedAmount);

        // See the deposit flow for the same PENDING-then-mark pattern. The
        // ledger is the single source of truth for "did we try?" — Oradian
        // is the source of truth for "did the money move?". Reconciliation
        // joins the two.
        Transaction ledger = transactionService.openPending(Transaction.builder()
                .transactionType(TransactionType.WITHDRAWAL)
                .customerPhone(phoneNumber)
                .sourceAccountId(request.getAccountID())
                .amount(parsedAmount)
                .paymentMethodName(request.getPaymentMethodName())
                .notes(request.getNotes())
                .transactionDate(request.getTransactionDate())
                .transactionBranchId(request.getTransactionBranchID())
                .idempotencyKey(httpRequest.getHeader(IDEMPOTENCY_HEADER))
                .build());

        log.info("POST /payments/withdraw txId={} account={} amount={} paymentMethod={} txnDate={}",
                ledger.getId(), request.getAccountID(), request.getAmount(),
                request.getPaymentMethodName(), request.getTransactionDate());

        WithdrawalResponse response;
        try {
            response = oradianMiddlewareClient.submitWithdrawal(request);
        } catch (OradianMiddlewareException ex) {
            try {
                transactionService.markFailed(ledger.getId(), ex);
            } catch (Exception fail) {
                log.error("markFailed failed for txId={}; row stays PENDING", ledger.getId(), fail);
            }
            throw ex;
        }

        try {
            transactionService.markSucceeded(ledger.getId(),
                    response.getTransactionID(), response.getReferenceNumber(),
                    response.getCommandID());
        } catch (Exception ex) {
            log.error("Oradian withdrawal succeeded but markSucceeded failed for txId={} oradianTxn={}",
                    ledger.getId(), response.getTransactionID(), ex);
        }
        return ResponseEntity.ok(ApiResult.ok("Withdrawal submitted", response));
    }

    /**
     * Parse the FE-supplied amount string to a positive BigDecimal so the
     * ledger column can take it as NUMERIC(19,4). Catches the things the
     * pure {@code @NotBlank} string check on the DTO can't: non-numeric
     * input, zero, negatives, and precision past four decimal places.
     * Beyond-cap velocity / daily-limit checks are a separate concern and
     * belong in their own service — this is just the wire-format guard.
     *
     * <p>Throws {@link IllegalArgumentException} on any failure;
     * {@code GlobalExceptionHandler} maps that to a 400 with the message
     * surfaced in the {@code ApiResult.message} field.
     */
    private static BigDecimal parsePositiveAmount(String amount) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(amount);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount must be a valid decimal number");
        }
        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (parsed.scale() > 4) {
            throw new IllegalArgumentException("amount must have at most 4 decimal places");
        }
        return parsed;
    }

    @GetMapping("/transactions")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the authenticated customer's transaction history",
            description = """
                    Reads the local payment-service ledger filtered to the
                    JWT-derived `phoneNumber` claim — customers see only
                    their own transactions, regardless of which of their
                    Oradian accounts the money moved through. Includes
                    PENDING rows so the FE can render in-flight transfers
                    that haven't yet reconciled with Oradian.

                    Defaults to the last 30 days, newest first, 20 rows
                    per page. Override via `fromDate` / `toDate` (ISO-8601)
                    and `page` / `size`. Page size is capped at 100 to
                    keep the response bounded.

                    Sensitive fields (idempotency key, correlation id,
                    internal command IDs) are stripped before serialisation
                    — see `TransactionView`.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Page of transactions, empty array when none in the window",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TransactionHistoryResponse.class),
                            examples = @ExampleObject(name = "Two transactions", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transactions retrieved",
                                      "data": {
                                        "transactions": [
                                          {
                                            "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                            "type": "TRANSFER",
                                            "status": "SUCCEEDED",
                                            "sourceAccountId": "A000001",
                                            "destinationAccountId": "A000002",
                                            "amount": 123.00,
                                            "notes": "Lunch",
                                            "transactionDate": "2026-05-18",
                                            "createdAt": "2026-05-18T10:30:00Z",
                                            "completedAt": "2026-05-18T10:30:01.523Z",
                                            "oradianTransactionId": "1155",
                                            "oradianReferenceNumber": "ref-9999"
                                          },
                                          {
                                            "id": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                            "type": "WITHDRAWAL",
                                            "status": "FAILED",
                                            "sourceAccountId": "A000001",
                                            "amount": 50.00,
                                            "paymentMethodName": "Cash",
                                            "transactionDate": "2026-05-17",
                                            "transactionBranchId": "MobileBanking",
                                            "createdAt": "2026-05-17T14:22:00Z",
                                            "completedAt": "2026-05-17T14:22:00.812Z",
                                            "failureCode": "UPSTREAM_REJECTED",
                                            "failureMessage": "Insufficient funds"
                                          }
                                        ],
                                        "page": 0,
                                        "size": 20,
                                        "totalElements": 2,
                                        "totalPages": 1
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "fromDate is after toDate, dates are in the wrong format, or size > 100",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Reversed dates", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "fromDate must be on or before toDate",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token, or token has no phoneNumber claim")
    })
    public ResponseEntity<ApiResult<TransactionHistoryResponse>> listTransactions(
            HttpServletRequest httpRequest,
            @RequestParam(name = "fromDate", required = false) LocalDate fromDate,
            @RequestParam(name = "toDate", required = false) LocalDate toDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_HISTORY_PAGE_SIZE) int size) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized("Missing Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            return unauthorized("Invalid or expired bearer token");
        }
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null) {
            return unauthorized(
                    "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/transactions");
        }

        // Default window: last DEFAULT_HISTORY_WINDOW_DAYS days. Caller may
        // narrow or widen — the only validation we enforce here is "from
        // before to" + a sane page size; date upper-bound clamping is left
        // to operational policy.
        LocalDate today = LocalDate.now();
        LocalDate effectiveTo = toDate == null ? today : toDate;
        LocalDate effectiveFrom = fromDate == null
                ? effectiveTo.minusDays(DEFAULT_HISTORY_WINDOW_DAYS)
                : fromDate;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("fromDate must be on or before toDate");
        }

        int clampedSize = Math.min(Math.max(size, 1), MAX_HISTORY_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);
        var pageable = PageRequest.of(clampedPage, clampedSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Transaction> rows = transactionRepository
                .findByCustomerPhoneAndTransactionDateBetween(
                        phoneNumber, effectiveFrom, effectiveTo, pageable);
        Page<TransactionView> view = rows.map(TransactionView::from);

        log.info("GET /payments/transactions phone={} from={} to={} page={} size={} total={}",
                MsisdnMasking.mask(phoneNumber), effectiveFrom, effectiveTo,
                clampedPage, clampedSize, rows.getTotalElements());

        return ResponseEntity.ok(
                ApiResult.ok("Transactions retrieved", TransactionHistoryResponse.from(view)));
    }

    @GetMapping("/transactions/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get one of the authenticated customer's transactions by id",
            description = """
                    Returns the {@link TransactionView} for the requested
                    ledger row. The receipt screen / share-receipt flow on
                    the FE reads this endpoint to render details for a
                    single transaction the customer tapped on in the
                    history list.

                    Ownership: the row's {@code customer_phone} MUST match
                    the JWT-derived phoneNumber claim. A mismatch surfaces
                    as 404 (not 403) — we don't leak the existence of
                    transactions that belong to other customers, so a
                    caller probing IDs can't tell apart "doesn't exist"
                    and "belongs to someone else".
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Transaction found and owned by the caller",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = TransactionView.class),
                            examples = @ExampleObject(name = "Receipt", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Transaction retrieved",
                                      "data": {
                                        "id": "a3b9c1d2-1234-5678-9abc-def012345678",
                                        "type": "TRANSFER",
                                        "status": "SUCCEEDED",
                                        "sourceAccountId": "A000001",
                                        "destinationAccountId": "A000002",
                                        "amount": 123.00,
                                        "notes": "Lunch",
                                        "transactionDate": "2026-05-18",
                                        "createdAt": "2026-05-18T10:30:00Z",
                                        "completedAt": "2026-05-18T10:30:01.523Z",
                                        "oradianTransactionId": "1155",
                                        "oradianReferenceNumber": "ref-9999"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid bearer token, or token has no phoneNumber claim"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Transaction does not exist OR belongs to a different customer " +
                            "(merged on purpose so existence isn't leaked)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "Transaction not found",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<TransactionView>> getTransaction(
            HttpServletRequest httpRequest,
            @PathVariable("id") UUID id) {

        String authHeader = httpRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized("Missing Bearer token");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.isTokenValid(token)) {
            return unauthorized("Invalid or expired bearer token");
        }
        String phoneNumber = jwtUtil.extractPhoneNumber(token);
        if (phoneNumber == null) {
            return unauthorized(
                    "Token has no phoneNumber claim; only CUSTOMER tokens can call /payments/transactions/{id}");
        }

        Transaction tx = transactionRepository.findById(id).orElse(null);
        // Merge "not found" + "found-but-not-yours" into one 404 response so
        // a caller probing UUIDs can't tell which transactions exist.
        if (tx == null || !phoneNumber.equals(tx.getCustomerPhone())) {
            if (tx != null) {
                // Found, but the wrong owner — log so operators can spot a
                // probing customer / token reuse. Phone numbers are masked.
                log.warn("Transaction detail ownership mismatch txId={} requestedBy={} owner={}",
                        id, MsisdnMasking.mask(phoneNumber),
                        MsisdnMasking.mask(tx.getCustomerPhone()));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResult.<TransactionView>builder()
                            .code("404 NOT_FOUND")
                            .message("Transaction not found")
                            .data(null)
                            .build());
        }

        return ResponseEntity.ok(ApiResult.ok("Transaction retrieved", TransactionView.from(tx)));
    }

    private static <T> ResponseEntity<ApiResult<T>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.<T>builder()
                        .code("401 UNAUTHORIZED")
                        .message(message)
                        .data(null)
                        .build());
    }

    private static <T> ResponseEntity<ApiResult<T>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResult.<T>builder()
                        .code("403 FORBIDDEN")
                        .message(message)
                        .data(null)
                        .build());
    }
}
