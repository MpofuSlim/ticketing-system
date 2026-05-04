package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public LoyaltyRule create(@Valid @RequestBody Dtos.RuleRequest req) {
        return rules.createRule(tenantContext.requireTenantId(), req);
    }

    @GetMapping
    @Operation(summary = "List rules for the current tenant",
            description = "Returns every rule belonging to the tenant — both merchant-specific and tenant-wide. " +
                          "Useful to audit which earn rates are currently in effect.")
    public List<LoyaltyRule> list() {
        return rules.listRules(tenantContext.requireTenantId());
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a rule",
            description = "Stops the rule from being applied to future transactions. Past transactions that " +
                          "earned points under this rule are unaffected. Use this rather than deletion so " +
                          "audit history (rule_id stamped on every transaction) remains valid.")
    public LoyaltyRule deactivate(@PathVariable UUID id) {
        return rules.deactivateRule(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/campaigns")
    @Operation(summary = "Launch a time-bound campaign",
            description = "Creates a campaign that multiplies points earned during the [startsAt, endsAt] " +
                          "window. RulesEngine picks the highest-multiplier active campaign per transaction. " +
                          "Scope to a merchant (`merchantId` set) or the whole tenant.")
    public Campaign createCampaign(@Valid @RequestBody Dtos.CampaignRequest req) {
        return rules.createCampaign(tenantContext.requireTenantId(), req);
    }

    @GetMapping("/campaigns")
    @Operation(summary = "List campaigns for the current tenant",
            description = "Returns past, current, and future campaigns. Filter client-side by " +
                          "`startsAt`/`endsAt` to find live ones.")
    public List<Campaign> listCampaigns() {
        return rules.listCampaigns(tenantContext.requireTenantId());
    }
}
