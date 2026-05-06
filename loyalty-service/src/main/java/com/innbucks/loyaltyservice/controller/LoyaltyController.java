package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.*;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.service.LoyaltyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/loyalty")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Loyalty", description = "Tenant rules, customer balances, earn/redeem ledger.")
public class LoyaltyController {

    private final LoyaltyService loyaltyService;

    // ==================== RULES ====================

    @PutMapping("/rules/{tenantId}")
    @PreAuthorize("hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Create or update a tenant's loyalty rule",
            description = "Sets the earn rate (points credited per $1 of cash) and redeem rate "
                    + "(points required to offset $1) for the given tenant. MERCHANT_ADMIN/SUPER_ADMIN only.")
    public ResponseEntity<ApiResult<LoyaltyRuleDTO>> upsertRule(
            @PathVariable String tenantId,
            @Valid @RequestBody LoyaltyRuleDTO body) {
        log.info("PUT /loyalty/rules/{} earnRate={} redeemRate={}",
                tenantId, body.getEarnRate(), body.getRedeemRate());
        LoyaltyRule saved = loyaltyService.upsertRule(tenantId, body);
        return ResponseEntity.ok(ApiResult.ok("Rule saved", toDTO(saved)));
    }

    @GetMapping("/rules/{tenantId}")
    @Operation(summary = "Read tenant's effective loyalty rule",
            description = "Returns the configured rule, or a synthetic default rule "
                    + "(driven by app.loyalty.default-* config) if the tenant hasn't customised one.")
    public ResponseEntity<ApiResult<LoyaltyRuleDTO>> getRule(@PathVariable String tenantId) {
        log.debug("GET /loyalty/rules/{}", tenantId);
        return ResponseEntity.ok(ApiResult.ok("Rule retrieved",
                toDTO(loyaltyService.getOrDefaultRule(tenantId))));
    }

    // ==================== BALANCE ====================

    @GetMapping("/balance")
    @Operation(summary = "Read a customer's points balance for a given tenant")
    public ResponseEntity<ApiResult<BalanceResponseDTO>> getBalance(
            @RequestParam String customerId,
            @RequestParam String tenantId) {
        log.debug("GET /loyalty/balance customerId={} tenantId={}", customerId, tenantId);
        return ResponseEntity.ok(ApiResult.ok("Balance retrieved",
                loyaltyService.getBalance(customerId, tenantId)));
    }

    // ==================== EARN ====================

    @PostMapping("/earn")
    @Operation(summary = "Credit points based on a cash purchase",
            description = "Idempotent on (customer, tenant, reference). Points credited = cashAmount × earnRate.")
    public ResponseEntity<ApiResult<LoyaltyTransactionDTO>> earn(@Valid @RequestBody EarnRequestDTO body) {
        log.info("POST /loyalty/earn customerId={} tenantId={} cashAmount={} reference={}",
                body.getCustomerId(), body.getTenantId(), body.getCashAmount(), body.getReference());
        return ResponseEntity.ok(ApiResult.ok("Points credited", loyaltyService.earn(body)));
    }

    // ==================== REDEEM ====================

    @PostMapping("/redeem")
    @Operation(summary = "Debit points from a customer's balance",
            description = "Idempotent on (customer, tenant, reference). 422 if balance is insufficient.")
    public ResponseEntity<ApiResult<LoyaltyTransactionDTO>> redeem(@Valid @RequestBody RedeemRequestDTO body) {
        log.info("POST /loyalty/redeem customerId={} tenantId={} points={} reference={}",
                body.getCustomerId(), body.getTenantId(), body.getPoints(), body.getReference());
        return ResponseEntity.ok(ApiResult.ok("Points redeemed", loyaltyService.redeem(body)));
    }

    private LoyaltyRuleDTO toDTO(LoyaltyRule rule) {
        return LoyaltyRuleDTO.builder()
                .tenantId(rule.getTenantId())
                .earnRate(rule.getEarnRate())
                .redeemRate(rule.getRedeemRate())
                .active(rule.isActive())
                .build();
    }
}
