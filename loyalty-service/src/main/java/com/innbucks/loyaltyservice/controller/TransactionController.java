package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RedemptionService;
import com.innbucks.loyaltyservice.service.TransactionService;
import com.innbucks.loyaltyservice.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty")
@Tag(name = "Transactions",
     description = "Points lifecycle: earn, redeem, adjust, reverse, transfer. Every successful transaction " +
                   "writes an immutable row to `points_ledger` and stamps the rule_id/campaign_id used, " +
                   "so balances can always be reconstructed from the ledger. Requires X-Tenant-Id.")
public class TransactionController {

    private final TransactionService transactions;
    private final TransferService transferService;
    private final RedemptionService redemptionService;
    private final TenantContext tenantContext;

    public TransactionController(TransactionService transactions, TransferService transferService,
                                 RedemptionService redemptionService, TenantContext tenantContext) {
        this.transactions = transactions;
        this.transferService = transferService;
        this.redemptionService = redemptionService;
        this.tenantContext = tenantContext;
    }

    @PostMapping("/transactions")
    @Operation(summary = "Post an earn transaction",
            description = "Awards points for a customer purchase or QR pay. RulesEngine selects the most " +
                          "specific active rule + highest active campaign multiplier and writes one row to " +
                          "`points_ledger`. Idempotent on `(merchantId, reference)` — duplicate references " +
                          "return DUPLICATE_REFERENCE so POS retries can be safely repeated. Rejects with " +
                          "USER_BLOCKED if the loyalty user is blocked from the program.")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> post(@Valid @RequestBody Dtos.TransactionRequest req) {
        Dtos.TransactionResponse data = transactions.post(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Transaction posted successfully", data));
    }

    @PostMapping("/transactions/{id}/reverse")
    @Operation(summary = "Reverse a transaction",
            description = "Issues a compensating ledger entry that negates the points originally awarded by " +
                          "transaction `{id}`. The original row is preserved (immutable ledger). Used for " +
                          "refunds and chargebacks. Optional JSON body: `{ \"reason\": \"...\" }`.")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> reverse(@PathVariable UUID id,
                                            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        Dtos.TransactionResponse data = transactions.reverse(tenantContext.requireTenantId(), id, reason);
        return ResponseEntity.ok(ApiResult.ok("Transaction reversed successfully", data));
    }

    @PostMapping("/transactions/adjust")
    @Operation(summary = "Manually adjust a user's points",
            description = "Operator escape hatch — credits or debits points outside the normal earn/redeem " +
                          "flow. Body: `{ userId, merchantId, points, reason }` where `points` may be " +
                          "positive (credit) or negative (debit). Always recorded in the ledger with " +
                          "type=ADJUSTMENT for audit.")
    public ResponseEntity<ApiResult<Dtos.TransactionResponse>> adjust(@RequestBody Map<String, Object> body) {
        UUID userId = UUID.fromString(String.valueOf(body.get("userId")));
        UUID merchantId = UUID.fromString(String.valueOf(body.get("merchantId")));
        BigDecimal points = new BigDecimal(String.valueOf(body.get("points")));
        String reason = (String) body.get("reason");
        Dtos.TransactionResponse data = transactions.adjust(tenantContext.requireTenantId(),
                userId, merchantId, points, reason);
        return ResponseEntity.ok(ApiResult.ok("Adjustment applied successfully", data));
    }

    @GetMapping("/users/{id}/transactions")
    @Operation(summary = "Get a user's recent transactions",
            description = "Returns the most recent transactions for the loyalty user `{id}` (the LoyaltyUser " +
                          "UUID, not the user-service userId). Powers the SuperApp activity feed.")
    public ResponseEntity<ApiResult<PageResponse<Dtos.TransactionResponse>>> recent(@PathVariable UUID id,
                                                                                    @ParameterObject Pageable pageable) {
        PageResponse<Dtos.TransactionResponse> data = PageResponse.from(transactions.recentForUser(id, pageable));
        return ResponseEntity.ok(ApiResult.ok("Transactions retrieved successfully", data));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer points between users (P2P)",
            description = "Moves points from `fromUserId` to `toUserId` atomically using canonical-order " +
                          "wallet locking to avoid deadlocks. Both must belong to the current tenant. " +
                          "Returns the sender's new balance.")
    public ResponseEntity<ApiResult<Map<String, Object>>> transfer(@Valid @RequestBody Dtos.TransferRequest req) {
        BigDecimal balance = transferService.transfer(tenantContext.requireTenantId(), req);
        Map<String, Object> data = Map.of("status", "OK", "newSenderBalance", balance);
        return ResponseEntity.ok(ApiResult.ok("Transfer completed successfully", data));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem points (raw, non-voucher)",
            description = "Burns points from the user's main wallet at the merchant's redeem rate without " +
                          "going through a voucher. Used by checkout flows that apply a points discount " +
                          "directly. For voucher-based redemption, use `POST /api/loyalty/vouchers/redeem`.")
    public ResponseEntity<ApiResult<Map<String, Object>>> redeem(@Valid @RequestBody Dtos.RedemptionRequest req) {
        BigDecimal balance = redemptionService.redeemPoints(tenantContext.requireTenantId(), req);
        Map<String, Object> data = Map.of("status", "OK", "newBalance", balance);
        return ResponseEntity.ok(ApiResult.ok("Points redeemed successfully", data));
    }

    @PostMapping("/convert-to-airtime")
    @Operation(summary = "Convert points to airtime (disabled)",
            description = "Placeholder for the M-Pesa/airtime integration. Returns NOT_ENABLED in this build. " +
                          "Kept on the API surface so client apps can detect feature availability without " +
                          "version sniffing.")
    public ResponseEntity<ApiResult<Map<String, Object>>> convertToAirtime() {
        Map<String, Object> data = Map.of(
                "status", "NOT_ENABLED",
                "message", "M-Pesa / airtime conversion is not enabled in this build."
        );
        return ResponseEntity.ok(ApiResult.ok("Airtime conversion not enabled", data));
    }
}
