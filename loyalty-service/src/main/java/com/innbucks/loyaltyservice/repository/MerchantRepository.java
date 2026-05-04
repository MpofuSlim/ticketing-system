package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByTenantId(UUID tenantId);
    Page<Merchant> findByTenantId(UUID tenantId, Pageable pageable);
    long countByTenantIdAndStatus(UUID tenantId, Merchant.Status status);
}
