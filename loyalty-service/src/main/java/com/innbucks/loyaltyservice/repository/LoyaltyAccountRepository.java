package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByCustomerIdAndTenantId(String customerId, String tenantId);
}
