package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    Optional<LoyaltyTransaction> findByAccountIdAndTypeAndReference(
            Long accountId, LoyaltyTransaction.Type type, String reference);

    List<LoyaltyTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId);
}
