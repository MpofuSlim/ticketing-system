package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByUserId(UUID userId);

    Optional<Wallet> findFirstByUserIdAndType(UUID userId, Wallet.Type type);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> lockById(@Param("id") UUID id);

    @Query("SELECT COALESCE(SUM(w.balance), 0) FROM Wallet w WHERE w.tenantId = :tenantId")
    BigDecimal sumBalanceByTenant(@Param("tenantId") UUID tenantId);

    // One-query balance aggregation across multiple users. Used by
    // /loyalty/users/me/wallet to avoid N round trips when a customer has
    // LoyaltyUser projections in many tenants.
    @Query("SELECT w.userId, COALESCE(SUM(w.balance), 0) FROM Wallet w " +
            "WHERE w.userId IN :userIds GROUP BY w.userId")
    List<Object[]> sumBalanceGroupedByUserId(@Param("userIds") List<UUID> userIds);

    /**
     * Paged scan of wallets that have been bound to an Oradian LPW
     * account — used by the nightly balance-audit job. Pagination so a
     * 10M-wallet deployment doesn't OOM the loyalty pod.
     */
    Page<Wallet> findByOradianAccountIdIsNotNull(Pageable pageable);
}
