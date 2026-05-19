package com.innbucks.loyaltyservice.repository;

import com.innbucks.loyaltyservice.entity.OradianSyncTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OradianSyncTransactionRepository
        extends JpaRepository<OradianSyncTransaction, UUID> {

    /**
     * Reconciliation finder: rows still PENDING whose {@code created_at}
     * is older than the supplied cutoff. The {@code LoyaltyReconciliationJob}
     * scans this every N minutes and finalises each row by polling Oradian
     * for the original outcome (the middleware's idempotency-key replay
     * makes that safe).
     *
     * <p>Backed by the {@code idx_oradian_sync_transactions_pending}
     * partial index (only PENDING rows are indexed there) so the scan
     * stays cheap regardless of the SUCCEEDED backlog.
     */
    @Query("SELECT t FROM OradianSyncTransaction t " +
            "WHERE t.status = com.innbucks.loyaltyservice.entity.OradianSyncTransaction.Status.PENDING " +
            "  AND t.createdAt < :cutoff " +
            "ORDER BY t.createdAt ASC")
    List<OradianSyncTransaction> findStalePending(Instant cutoff);

    /**
     * Audit pivot: every sync attempt against a given Oradian account,
     * newest first. Used by the balance-audit job and by ad-hoc forensics
     * when a customer disputes their LPW balance.
     */
    List<OradianSyncTransaction> findByOradianAccountIdOrderByCreatedAtDesc(String oradianAccountId);
}
