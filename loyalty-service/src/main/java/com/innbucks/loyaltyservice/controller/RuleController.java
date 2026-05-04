package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.ApiResult;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.dto.PageResponse;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/rules")
@Tag(name = "Rules & Campaigns",
     description = "Earn-rate configuration. **Rules** define how many points are awarded per currency unit " +
                   "for each transaction type (PURCHASE, QR_PAY, etc.) and may include caps and per-pocket " +
                   "targeting. **Campaigns** layer time-bound multipliers on top of rules (e.g. 2x weekend). " +
                   "Both are evaluated by RulesEngine on every transaction. Requires X-Tenant-Id.")
public class RuleController {

    private final RuleAdminService rules;
    private final TenantContext tenantContext;

    public RuleController(RuleAdminService rules, TenantContext tenantContext) {
        this.rules = rules;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    @Operation(summary = "Create a loyalty rule",
            description = "Creates an earn-rate rule scoped to either a specific merchant (`merchantId` set) " +
                          "or the whole tenant (`merchantId` null). Merchant-specific rules override tenant " +
                          "defaults. `pointsPerUnit` × `multiplier` is applied to the transaction amount; " +
                          "`maxPointsPerTxn` caps the result if set.")
    public ResponseEntity<ApiResult<LoyaltyRule>> create(@Valid @RequestBody Dtos.RuleRequest req) {
        LoyaltyRule data = rules.createRule(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Rule created successfully", data));
    }

    @GetMapping
    @Operation(summary = "List rules for the current tenant",
            description = "Returns every rule belonging to the tenant — both merchant-specific and tenant-wide. " +
                          "Useful to audit which earn rates are currently in effect.")
    public ResponseEntity<ApiResult<PageResponse<LoyaltyRule>>> list(@ParameterObject Pageable pageable) {
        PageResponse<LoyaltyRule> data = PageResponse.from(
                rules.listRules(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Rules retrieved successfully", data));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a rule",
            description = "Stops the rule from being applied to future transactions. Past transactions that " +
                          "earned points under this rule are unaffected. Use this rather than deletion so " +
                          "audit history (rule_id stamped on every transaction) remains valid.")
    public ResponseEntity<ApiResult<LoyaltyRule>> deactivate(@PathVariable UUID id) {
        LoyaltyRule data = rules.deactivateRule(tenantContext.requireTenantId(), id);
        return ResponseEntity.ok(ApiResult.ok("Rule deactivated successfully", data));
    }

    @PostMapping("/campaigns")
    @Operation(summary = "Launch a time-bound campaign",
            description = "Creates a campaign that multiplies points earned during the [startsAt, endsAt] " +
                          "window. RulesEngine picks the highest-multiplier active campaign per transaction. " +
                          "Scope to a merchant (`merchantId` set) or the whole tenant.")
    public ResponseEntity<ApiResult<Campaign>> createCampaign(@Valid @RequestBody Dtos.CampaignRequest req) {
        Campaign data = rules.createCampaign(tenantContext.requireTenantId(), req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Campaign created successfully", data));
    }

    @GetMapping("/campaigns")
    @Operation(summary = "List campaigns for the current tenant",
            description = "Returns past, current, and future campaigns. Filter client-side by " +
                          "`startsAt`/`endsAt` to find live ones.")
    public ResponseEntity<ApiResult<PageResponse<Campaign>>> listCampaigns(@ParameterObject Pageable pageable) {
        PageResponse<Campaign> data = PageResponse.from(
                rules.listCampaigns(tenantContext.requireTenantId(), pageable));
        return ResponseEntity.ok(ApiResult.ok("Campaigns retrieved successfully", data));
    }
}
