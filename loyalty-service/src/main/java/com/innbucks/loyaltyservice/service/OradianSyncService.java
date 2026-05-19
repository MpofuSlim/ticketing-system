package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.OradianMiddlewareClient;
import com.innbucks.loyaltyservice.client.OradianMiddlewareException;
import com.innbucks.loyaltyservice.client.OradianMiddlewareTransientException;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.CreditDepositAccountResponse;
import com.innbucks.loyaltyservice.client.dto.DepositAccount;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountRequest;
import com.innbucks.loyaltyservice.client.dto.WithdrawDepositAccountResponse;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.OradianSyncTransaction;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.OradianSyncTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mirrors every {@link WalletService#apply} delta to the customer's
 * LPW deposit account on Oradian. Called from {@code WalletService.apply}
 * when {@code loyalty.oradian-sync.enabled=true}; otherwise the apply
 * stays local-only and this service is a no-op.
 *
 * <h2>Sync-first model</h2>
 * The middleware call happens BEFORE the local wallet mutation. On
 * upstream success the SUCCEEDED {@link OradianSyncTransaction} row
 * commits in the same transaction as the {@code wallet.balance}
 * update + {@code points_ledger} insert. On upstream failure the
 * FAILED row is written in a REQUIRES_NEW transaction (so it survives
 * the rollback triggered when this method throws) and the apply
 * rolls back — the customer's earn / spend doesn't appear locally
 * either, matching the agreed strict-consistency model.
 *
 * <h2>Account-id discovery</h2>
 * On the first sync attempt for a wallet whose
 * {@link Wallet#getOradianAccountId()} is still null, we call
 * {@code GET /internal/customers/{msisdn}/deposits} and pick the
 * row where {@code productID == "LPW"}. That ID is then cached on
 * the wallet row for all subsequent syncs.
 *
 * <h2>Skip cases</h2>
 * Two situations skip the sync entirely and let the apply proceed
 * local-only:
 * <ul>
 *   <li>The {@link LoyaltyUser} is in {@code PENDING} status — they
 *       don't have an Oradian customer yet (pre-tier-2). Points
 *       accumulate locally and get backfilled to Oradian on
 *       tier-2 promotion (separate flow).</li>
 *   <li>The customer has no LPW account on Oradian (rare —
 *       middleware's customer-create normally provisions all three
 *       configured products). We log a warning and skip rather than
 *       block the customer's earn.</li>
 * </ul>
 */
@Service
@Slf4j
public class OradianSyncService {

    private static final String LPW_PRODUCT_ID = "LPW";

    private final boolean enabled;
    private final String creditPaymentMethod;
    private final String debitPaymentMethod;
    private final String transactionBranchId;

    private final OradianMiddlewareClient client;
    private final LoyaltyUserRepository userRepository;
    private final OradianSyncTransactionRepository syncRepository;
    private final TransactionTemplate requiresNewTemplate;

    public OradianSyncService(
            @Value("${loyalty.oradian-sync.enabled:false}") boolean enabled,
            @Value("${loyalty.oradian-sync.credit-payment-method:Cash}") String creditPaymentMethod,
            @Value("${loyalty.oradian-sync.debit-payment-method:Cash}") String debitPaymentMethod,
            @Value("${loyalty.oradian-sync.transaction-branch-id:MobileBanking}") String transactionBranchId,
            OradianMiddlewareClient client,
            LoyaltyUserRepository userRepository,
            OradianSyncTransactionRepository syncRepository,
            PlatformTransactionManager transactionManager) {
        this.enabled = enabled;
        this.creditPaymentMethod = creditPaymentMethod;
        this.debitPaymentMethod = debitPaymentMethod;
        this.transactionBranchId = transactionBranchId;
        this.client = client;
        this.userRepository = userRepository;
        this.syncRepository = syncRepository;
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Public skip-test used by {@link WalletService#apply} before
     * entering this code path. Equivalent to "is the feature flag on".
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sync {@code delta} (positive = credit, negative = withdraw) to
     * the wallet's LPW Oradian account. Writes a SUCCEEDED
     * {@link OradianSyncTransaction} row on the main transaction on
     * success. On failure, writes a FAILED row in a REQUIRES_NEW
     * transaction and throws — the caller is expected to be inside an
     * {@code @Transactional} that will roll back its local mutations.
     *
     * <p>Mutates {@link Wallet#setOradianAccountId} on first sync if
     * the field was null and lazy discovery succeeded. The wallet
     * update rides on the main transaction.
     */
    public void syncDelta(Wallet wallet, BigDecimal delta, UUID sourceTransactionId, String reason) {
        if (!enabled) {
            return;
        }
        if (delta.signum() == 0) {
            // No-op delta — nothing to sync. Possible if a caller
            // applied a zero (e.g. from an aborted rule evaluation).
            return;
        }

        LoyaltyUser user = userRepository.findById(wallet.getUserId())
                .orElseThrow(() -> LoyaltyException.notFound("loyalty user"));

        if (user.getStatus() == LoyaltyUser.Status.PENDING) {
            log.debug("Oradian sync skipped — user PENDING walletId={} userId={}",
                    wallet.getId(), user.getId());
            return;
        }

        String accountId = resolveOradianAccountId(wallet, user);
        if (accountId == null) {
            log.warn("Oradian sync skipped — no LPW account discoverable for userId={} walletId={}",
                    user.getId(), wallet.getId());
            return;
        }

        UUID syncTxId = UUID.randomUUID();
        String correlationId = MDC.get("correlationId");
        try {
            if (delta.signum() > 0) {
                CreditDepositAccountResponse resp = client.creditDepositAccount(
                        buildCreditRequest(accountId, delta, reason), syncTxId.toString());
                recordSucceededCredit(syncTxId, wallet, delta, reason,
                        sourceTransactionId, accountId, correlationId, resp);
            } else {
                WithdrawDepositAccountResponse resp = client.withdrawFromDepositAccount(
                        buildWithdrawRequest(accountId, delta.abs(), reason), syncTxId.toString());
                recordSucceededWithdraw(syncTxId, wallet, delta, reason,
                        sourceTransactionId, accountId, correlationId, resp);
            }
        } catch (OradianMiddlewareException e) {
            recordFailedInNewTransaction(syncTxId, wallet, delta, reason,
                    sourceTransactionId, accountId, correlationId, e);
            // Rethrow as a typed loyalty-domain error so the caller's
            // @Transactional rolls back and the customer-facing
            // response shape stays consistent with other loyalty failures.
            throw LoyaltyException.badRequest(
                    "ORADIAN_SYNC_FAILED",
                    "Oradian sync failed: " + e.getMessage());
        }
    }

    /**
     * Look up the wallet's LPW Oradian account ID. Cache hit: return
     * the stored value. Cache miss: call the middleware's
     * customer-deposits list, filter {@code productID == "LPW"},
     * stash the result on the wallet row, return it.
     *
     * <p>Returns {@code null} if no LPW account exists on Oradian
     * (caller skips sync in that case).
     */
    private String resolveOradianAccountId(Wallet wallet, LoyaltyUser user) {
        if (wallet.getOradianAccountId() != null && !wallet.getOradianAccountId().isBlank()) {
            return wallet.getOradianAccountId();
        }
        List<DepositAccount> deposits = client.getDepositsForMsisdn(user.getPhoneNumber());
        Optional<DepositAccount> lpw = deposits.stream()
                .filter(d -> LPW_PRODUCT_ID.equals(d.productID()))
                .findFirst();
        if (lpw.isEmpty()) {
            return null;
        }
        String id = lpw.get().ID();
        if (id == null || id.isBlank()) {
            // Oradian sent the LPW row with an empty external ID —
            // can't act on this. Surface as a skip rather than a
            // crash.
            log.warn("LPW account row has no external ID userId={} (Oradian config issue)",
                    user.getId());
            return null;
        }
        // Cache for future syncs. This update rides on the calling
        // @Transactional; if the apply rolls back later, the cached
        // ID is lost too — fine, we'll re-discover on the next try.
        wallet.setOradianAccountId(id);
        log.info("Discovered LPW account walletId={} userId={} accountId={}",
                wallet.getId(), user.getId(), id);
        return id;
    }

    private CreditDepositAccountRequest buildCreditRequest(String accountId, BigDecimal positiveDelta, String reason) {
        return new CreditDepositAccountRequest(
                accountId,
                creditPaymentMethod,
                LocalDate.now(),
                positiveDelta.toPlainString(),
                transactionBranchId,
                reason,
                null);
    }

    private WithdrawDepositAccountRequest buildWithdrawRequest(String accountId, BigDecimal absoluteDelta, String reason) {
        return new WithdrawDepositAccountRequest(
                Boolean.FALSE,
                accountId,
                debitPaymentMethod,
                LocalDate.now(),
                absoluteDelta.toPlainString(),
                transactionBranchId,
                reason);
    }

    private void recordSucceededCredit(UUID syncTxId, Wallet wallet, BigDecimal delta, String reason,
                                       UUID sourceTransactionId, String accountId, String correlationId,
                                       CreditDepositAccountResponse resp) {
        OradianSyncTransaction tx = baseRow(syncTxId, wallet, delta, reason,
                sourceTransactionId, accountId, correlationId);
        tx.setStatus(OradianSyncTransaction.Status.SUCCEEDED);
        tx.setOradianTransactionId(resp.transactionID());
        tx.setOradianCommandId(resp.commandID());
        tx.setOradianReferenceNumber(resp.referenceNumber());
        tx.setCompletedAt(Instant.now());
        syncRepository.save(tx);
    }

    private void recordSucceededWithdraw(UUID syncTxId, Wallet wallet, BigDecimal delta, String reason,
                                         UUID sourceTransactionId, String accountId, String correlationId,
                                         WithdrawDepositAccountResponse resp) {
        OradianSyncTransaction tx = baseRow(syncTxId, wallet, delta, reason,
                sourceTransactionId, accountId, correlationId);
        tx.setStatus(OradianSyncTransaction.Status.SUCCEEDED);
        tx.setOradianTransactionId(resp.transactionID());
        tx.setOradianCommandId(resp.commandID());
        tx.setOradianReferenceNumber(resp.referenceNumber());
        tx.setCompletedAt(Instant.now());
        syncRepository.save(tx);
    }

    /**
     * Persist a FAILED row in a brand-new transaction so it survives
     * the rollback triggered by the rethrow below. Without REQUIRES_NEW
     * the FAILED audit trail would be erased the moment the calling
     * {@code @Transactional} rolls back — losing the forensic record
     * of exactly why the customer's earn / spend failed.
     */
    private void recordFailedInNewTransaction(UUID syncTxId, Wallet wallet, BigDecimal delta, String reason,
                                              UUID sourceTransactionId, String accountId, String correlationId,
                                              OradianMiddlewareException e) {
        String classification = (e instanceof OradianMiddlewareTransientException)
                ? "UPSTREAM_UNAVAILABLE"
                : "UPSTREAM_REJECTED";
        try {
            requiresNewTemplate.execute(status -> {
                OradianSyncTransaction tx = baseRow(syncTxId, wallet, delta, reason,
                        sourceTransactionId, accountId, correlationId);
                tx.setStatus(OradianSyncTransaction.Status.FAILED);
                tx.setFailureCode(classification);
                tx.setFailureMessage(truncate(e.getMessage(), 500));
                tx.setCompletedAt(Instant.now());
                syncRepository.save(tx);
                return null;
            });
        } catch (RuntimeException writerEx) {
            // Don't shadow the original Oradian failure. The FAILED
            // audit row is best-effort; ops will still see the
            // ORADIAN_SYNC_FAILED log line below.
            log.error("Failed to persist FAILED OradianSyncTransaction syncTxId={} cause={}",
                    syncTxId, writerEx.toString());
        }
        log.warn("Oradian sync FAILED walletId={} delta={} reason={} classification={} upstreamStatus={} detail={}",
                wallet.getId(), delta, reason, classification, e.getStatusCode(), e.getMessage());
    }

    private OradianSyncTransaction baseRow(UUID syncTxId, Wallet wallet, BigDecimal delta, String reason,
                                           UUID sourceTransactionId, String accountId, String correlationId) {
        OradianSyncTransaction tx = new OradianSyncTransaction();
        tx.setId(syncTxId);
        tx.setTenantId(wallet.getTenantId());
        tx.setWalletId(wallet.getId());
        tx.setDeltaPoints(delta);
        tx.setReason(reason);
        tx.setOradianAccountId(accountId);
        tx.setSourceTransactionId(sourceTransactionId);
        tx.setCorrelationId(correlationId);
        tx.setCreatedAt(Instant.now());
        return tx;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }
}
