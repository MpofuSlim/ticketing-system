package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    Optional<Voucher> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Voucher v WHERE v.code = :code")
    Optional<Voucher> lockByCode(@Param("code") String code);

    List<Voucher> findByAssignedUserIdAndStatusIn(UUID userId, List<Voucher.Status> statuses);

    List<Voucher> findByTenantIdAndStatus(UUID tenantId, Voucher.Status status);

    @Query("SELECT v FROM Voucher v WHERE v.expiresAt IS NOT NULL AND v.expiresAt < :now AND v.status NOT IN " +
            "(com.innbucks.loyaltyservice.entity.Voucher.Status.REDEEMED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.EXPIRED, " +
            " com.innbucks.loyaltyservice.entity.Voucher.Status.REVOKED)")
    List<Voucher> findExpired(@Param("now") Instant now);

    long countByMerchantIdAndIssuedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByMerchantIdAndRedeemedAtBetween(UUID merchantId, Instant from, Instant to);

    long countByTenantIdAndStatus(UUID tenantId, Voucher.Status status);
}
