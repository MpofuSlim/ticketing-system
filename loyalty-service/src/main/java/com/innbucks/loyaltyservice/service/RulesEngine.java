package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.repository.CampaignRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class RulesEngine {

    private final LoyaltyRuleRepository rules;
    private final CampaignRepository campaigns;

    public RulesEngine(LoyaltyRuleRepository rules, CampaignRepository campaigns) {
        this.rules = rules;
        this.campaigns = campaigns;
    }

    public record Evaluation(BigDecimal points, UUID ruleId, UUID campaignId, String pocket) {}

    /**
     * Evaluate the most-specific applicable rule. Merchant-specific rules win
     * over global rules; the active campaign with the highest multiplier
     * stacks on top.
     */
    public Evaluation evaluate(UUID tenantId, UUID merchantId,
                               TransactionType type, BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        if (type == TransactionType.REFUND || type == TransactionType.ADJUSTMENT) {
            return new Evaluation(BigDecimal.ZERO, null, null, null);
        }

        var applicable = rules.findApplicable(tenantId, merchantId, type);
        Instant now = Instant.now();
        LoyaltyRule rule = applicable.stream()
                .filter(r -> r.getStartsAt() == null || !now.isBefore(r.getStartsAt()))
                .filter(r -> r.getEndsAt() == null || !now.isAfter(r.getEndsAt()))
                .findFirst().orElse(null);

        if (rule == null) {
            return new Evaluation(BigDecimal.ZERO, null, null, null);
        }

        BigDecimal points = amount.multiply(rule.getPointsPerUnit())
                .multiply(rule.getMultiplier());

        Campaign campaign = campaigns.findActive(tenantId, merchantId, type, now)
                .stream().findFirst().orElse(null);
        if (campaign != null) {
            points = points.multiply(campaign.getMultiplier());
        }

        if (rule.getMaxPointsPerTxn() != null && points.compareTo(rule.getMaxPointsPerTxn()) > 0) {
            points = rule.getMaxPointsPerTxn();
        }

        points = points.setScale(4, RoundingMode.HALF_UP);
        return new Evaluation(points, rule.getId(),
                campaign == null ? null : campaign.getId(), rule.getPocket());
    }
}
