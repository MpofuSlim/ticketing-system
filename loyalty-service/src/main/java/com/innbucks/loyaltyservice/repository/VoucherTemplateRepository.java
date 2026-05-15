package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.VoucherTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VoucherTemplateRepository extends JpaRepository<VoucherTemplate, UUID> {
    List<VoucherTemplate> findByTenantId(UUID tenantId);
    Page<VoucherTemplate> findByTenantId(UUID tenantId, Pageable pageable);
    List<VoucherTemplate> findByTenantIdAndMerchantId(UUID tenantId, UUID merchantId);
}
