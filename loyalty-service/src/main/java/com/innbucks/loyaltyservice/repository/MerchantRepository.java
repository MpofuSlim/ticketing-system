package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByTenantId(UUID tenantId);
    long countByTenantIdAndStatus(UUID tenantId, Merchant.Status status);
}
