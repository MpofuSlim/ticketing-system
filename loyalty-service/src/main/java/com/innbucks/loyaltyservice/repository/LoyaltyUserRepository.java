package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyUserRepository extends JpaRepository<LoyaltyUser, UUID> {
    Optional<LoyaltyUser> findByTenantIdAndPhone(UUID tenantId, String phone);
    List<LoyaltyUser> findByTenantId(UUID tenantId);
}
