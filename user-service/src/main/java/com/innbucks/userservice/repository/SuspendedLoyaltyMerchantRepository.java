package com.innbucks.userservice.repository;

import com.innbucks.userservice.entity.SuspendedLoyaltyMerchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SuspendedLoyaltyMerchantRepository extends JpaRepository<SuspendedLoyaltyMerchant, UUID> {

    @Query("SELECT s.merchantId FROM SuspendedLoyaltyMerchant s WHERE s.merchantId IN :ids")
    List<UUID> findSuspendedAmong(@Param("ids") Collection<UUID> merchantIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SuspendedLoyaltyMerchant s WHERE s.organizationId = :org")
    int deleteByOrganization(@Param("org") UUID organizationId);
}
