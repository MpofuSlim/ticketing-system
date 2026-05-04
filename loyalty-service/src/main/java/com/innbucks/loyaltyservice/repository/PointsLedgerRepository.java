package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.PointsLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PointsLedgerRepository extends JpaRepository<PointsLedger, UUID> {
    List<PointsLedger> findTop50ByWalletIdOrderByCreatedAtDesc(UUID walletId);
}
