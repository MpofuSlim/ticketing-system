package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Shop;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShopRepository extends JpaRepository<Shop, UUID> {
    List<Shop> findByTenantId(UUID tenantId);
    Page<Shop> findByTenantId(UUID tenantId, Pageable pageable);
    List<Shop> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId);
    Page<Shop> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId, Pageable pageable);
}
