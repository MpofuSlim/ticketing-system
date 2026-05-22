package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {

    Optional<LoyaltyTransaction> findFirstByMerchantIdAndReference(UUID merchantId, String reference);

    List<LoyaltyTransaction> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<LoyaltyTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<LoyaltyTransaction> findByTenantIdAndShopIdOrderByCreatedAtDesc(UUID tenantId, UUID shopId, Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta > 0 THEN t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.merchantId = :merchantId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsIssued(@Param("merchantId") UUID merchantId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN t.pointsDelta < 0 THEN -t.pointsDelta ELSE 0 END), 0)
        FROM LoyaltyTransaction t
        WHERE t.merchantId = :merchantId
          AND t.createdAt >= :from AND t.createdAt < :to
          AND t.status = com.innbucks.loyaltyservice.entity.LoyaltyTransaction.Status.POSTED
        """)
    BigDecimal sumPointsRedeemed(@Param("merchantId") UUID merchantId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("""
        SELECT t.type, COUNT(t)
        FROM LoyaltyTransaction t
        WHERE t.tenantId = :tenantId
          AND (:merchantId IS NULL OR t.merchantId = :merchantId)
          AND t.createdAt >= :from AND t.createdAt < :to
        GROUP BY t.type
        """)
    List<Object[]> countByType(@Param("tenantId") UUID tenantId,
                               @Param("merchantId") UUID merchantId,
                               @Param("from") Instant from,
                               @Param("to") Instant to);

    @Query("""
        SELECT COUNT(t) FROM LoyaltyTransaction t
        WHERE t.createdAt >= :from
        """)
    long countSince(@Param("from") Instant from);

    long countByCampaignId(UUID campaignId);
}
