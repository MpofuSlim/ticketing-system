package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyRuleRepository extends JpaRepository<LoyaltyRule, Long> {

    Optional<LoyaltyRule> findByTenantId(String tenantId);
}
