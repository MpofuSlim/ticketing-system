package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.DepositTransferRequest;
import innbucks.paymentservice.dto.DepositTransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * S2S transfer endpoints. Path lives under /internal/** which the api-gateway
 * doesn't route to payment-service — only the public /payments/** prefix is
 * routed — so this controller is reachable pod-to-pod only.
 *
 * payment-service has no security filter chain (intentional: see
 * GlobalExceptionHandler's note). The X-Internal-Token check is enforced
 * inline by the controller. Authorization (e.g. "does this customer own
 * fromAccountId?") is the caller's responsibility; we are a thin executor.
 */
@RestController
@RequestMapping("/internal/transfers")
@Slf4j
@Tag(name = "Internal Transfers",
     description = "Service-to-service Oradian deposit account transfers. " +
                   "X-Internal-Token gated; not reachable through the public api-gateway.")
public class InternalTransferController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final OradianMiddlewareClient oradianMiddlewareClient;
    private final String expectedInternalToken;

    public InternalTransferController(
            OradianMiddlewareClient oradianMiddlewareClient,
            @Value("${innbucks.internal-api-token:}") String expectedInternalToken) {
        this.oradianMiddlewareClient = oradianMiddlewareClient;
        this.expectedInternalToken = expectedInternalToken;
    }

    @PostMapping("/deposit")
    @Operation(summary = "Submit a deposit account transfer (S2S)",
            description = """
                    Submits a money transfer between two Oradian deposit accounts.
                    Forwards the request to Oradian middleware's S2S
                    /internal/transfers/deposit, which calls Oradian's
                    instafin.SubmitDepositAccountTransfer and returns the
                    assigned transactionID + reference. The upstream response
                    is passed through unchanged inside the standard ApiResult
                    envelope.

                    Authenticated by the shared `X-Internal-Token` (must match
                    `innbucks.internal-api-token`). The caller is responsible
                    for verifying that the customer initiating the transfer
                    actually owns `fromAccountId` — payment-service does not
                    re-check ownership.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Transfer submitted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
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
                                        "notes": "",
                                        "referenceNumber": "1234567980123",
                                        "transactionDate": "2020-02-28",
                                        "transactionID": "1155",
                                        "accountVersion": "",
                                        "customFields": ""
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failure"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Missing or invalid X-Internal-Token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Oradian middleware unreachable or upstream Oradian failed")
    })
    public ResponseEntity<ApiResult<DepositTransferResponse>> transferDeposit(
            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "Shared secret; must match `innbucks.internal-api-token`.")
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @Valid @RequestBody DepositTransferRequest request) {

        if (!constantTimeEquals(expectedInternalToken, internalToken)) {
            log.warn("Internal API auth failure on /internal/transfers/deposit");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResult.<DepositTransferResponse>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Invalid X-Internal-Token")
                            .data(null)
                            .build());
        }

        log.info("POST /internal/transfers/deposit from={} to={} amount={} txnDate={}",
                request.getFromAccountId(), request.getToAccountId(),
                request.getAmount(), request.getTransactionDate());

        DepositTransferResponse response =
                oradianMiddlewareClient.submitDepositTransfer(request);
        return ResponseEntity.ok(ApiResult.ok("Deposit transfer submitted", response));
    }

    /**
     * Constant-time compare so a timing oracle can't reveal the expected
     * token's length or matching prefix. Same pattern as Oradian middleware's
     * InternalApiProperties.matches.
     */
    private static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
