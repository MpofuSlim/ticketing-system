package com.innbucks.loyaltyservice.controller;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.security.TenantContext;
import com.innbucks.loyaltyservice.service.RuleAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loyalty/rules")
public class RuleController {

    private final RuleAdminService rules;
    private final TenantContext tenantContext;

    public RuleController(RuleAdminService rules, TenantContext tenantContext) {
        this.rules = rules;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    public LoyaltyRule create(@Valid @RequestBody Dtos.RuleRequest req) {
        return rules.createRule(tenantContext.requireTenantId(), req);
    }

    @GetMapping
    public List<LoyaltyRule> list() {
        return rules.listRules(tenantContext.requireTenantId());
    }

    @PostMapping("/{id}/deactivate")
    public LoyaltyRule deactivate(@PathVariable UUID id) {
        return rules.deactivateRule(tenantContext.requireTenantId(), id);
    }

    @PostMapping("/campaigns")
    public Campaign createCampaign(@Valid @RequestBody Dtos.CampaignRequest req) {
        return rules.createCampaign(tenantContext.requireTenantId(), req);
    }

    @GetMapping("/campaigns")
    public List<Campaign> listCampaigns() {
        return rules.listCampaigns(tenantContext.requireTenantId());
    }
}
