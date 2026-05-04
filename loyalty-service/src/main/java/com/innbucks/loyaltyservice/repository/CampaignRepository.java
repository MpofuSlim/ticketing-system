package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Campaign;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    @Query("""
        SELECT c FROM Campaign c
        WHERE c.tenantId = :tenantId
          AND (c.merchantId = :merchantId OR c.merchantId IS NULL)
          AND (c.transactionType = :type OR c.transactionType IS NULL)
          AND c.active = true
          AND c.startsAt <= :now AND c.endsAt >= :now
        ORDER BY c.multiplier DESC
        """)
    List<Campaign> findActive(@Param("tenantId") UUID tenantId,
                              @Param("merchantId") UUID merchantId,
                              @Param("type") TransactionType type,
                              @Param("now") Instant now);

    List<Campaign> findByTenantId(UUID tenantId);

    Page<Campaign> findByTenantId(UUID tenantId, Pageable pageable);
}
