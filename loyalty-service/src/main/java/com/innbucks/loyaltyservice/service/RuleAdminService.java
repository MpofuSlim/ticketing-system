package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RuleAdminService {

    private final LoyaltyRuleRepository rules;
    private final CampaignRepository campaigns;
    private final MerchantService merchants;

    public RuleAdminService(LoyaltyRuleRepository rules, CampaignRepository campaigns,
                            MerchantService merchants) {
        this.rules = rules;
        this.campaigns = campaigns;
        this.merchants = merchants;
    }

    public LoyaltyRule createRule(UUID tenantId, UUID merchantId, Dtos.RuleRequest req) {
        if (merchantId != null) merchants.requireMerchant(tenantId, merchantId);
        LoyaltyRule r = new LoyaltyRule();
        r.setTenantId(tenantId);
        r.setMerchantId(merchantId);
        r.setTransactionType(req.transactionType());
        r.setPointsPerUnit(req.pointsPerUnit());
        r.setMultiplier(req.multiplier() == null ? BigDecimal.ONE : req.multiplier());
        r.setMaxPointsPerTxn(req.maxPointsPerTxn());
        r.setPocket(req.pocket());
        r.setStartsAt(req.startsAt());
        r.setEndsAt(req.endsAt());
        return rules.save(r);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyRule> listRules(UUID tenantId) {
        return rules.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyRule> listRules(UUID tenantId, Pageable pageable) {
        return rules.findByTenantId(tenantId, pageable);
    }

    public LoyaltyRule deactivateRule(UUID tenantId, UUID ruleId) {
        LoyaltyRule r = rules.findById(ruleId).orElseThrow(() -> LoyaltyException.notFound("rule"));
        if (!r.getTenantId().equals(tenantId)) throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        r.setActive(false);
        return r;
    }

    public Campaign createCampaign(UUID tenantId, UUID merchantId, Dtos.CampaignRequest req) {
        if (merchantId != null) merchants.requireMerchant(tenantId, merchantId);
        if (req.endsAt().isBefore(req.startsAt())) {
            throw LoyaltyException.badRequest("BAD_DATES", "endsAt must be after startsAt");
        }
        Campaign c = new Campaign();
        c.setTenantId(tenantId);
        c.setMerchantId(merchantId);
        c.setName(req.name());
        c.setMultiplier(req.multiplier());
        c.setTransactionType(req.transactionType());
        c.setStartsAt(req.startsAt());
        c.setEndsAt(req.endsAt());
        return campaigns.save(c);
    }

    @Transactional(readOnly = true)
    public List<Campaign> listCampaigns(UUID tenantId) {
        return campaigns.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Page<Campaign> listCampaigns(UUID tenantId, Pageable pageable) {
        return campaigns.findByTenantId(tenantId, pageable);
    }
}
