package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class RedemptionService {

    private final UserService users;
    private final MerchantService merchants;
    private final WalletService walletService;
    private final LoyaltyTransactionRepository transactions;
    private final LoyaltyMetrics metrics;

    public RedemptionService(UserService users, MerchantService merchants,
                             WalletService walletService,
                             LoyaltyTransactionRepository transactions,
                             LoyaltyMetrics metrics) {
        this.users = users;
        this.merchants = merchants;
        this.walletService = walletService;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /**
     * Outcome of a redemption: both the new wallet balance and the ledger
     * transaction id. The id lets shop-checkout / receipts surface the
     * specific REDEMPTION row a customer can later quote for support.
     */
    public record RedemptionResult(UUID transactionId, BigDecimal balance) {}

    /**
     * Redeem points for in-platform credit (e.g. discount). Returns the new
     * balance plus the ledger transaction id for receipts/reconciliation.
     */
    public RedemptionResult redeemPoints(UUID tenantId, UUID merchantId, Dtos.RedemptionRequest req) {
        if (req.points() == null || req.points().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "Please enter an amount greater than zero.");
        }
        var u = users.require(tenantId, req.userId());
        // PENDING (not yet registered) users may accrue but not spend.
        users.requireSpendable(u);
        var m = merchants.requireMerchant(tenantId, merchantId);

        // Idempotency: when the caller supplies a stable reference (e.g. the
        // booking id), a repeat redeem must NOT debit the wallet a second time.
        // A retry (network blip, double-tap) replays the original redemption.
        // The uq_txn_merchant_reference partial unique index (V16, which covers
        // REDEMPTION rows) is the race backstop behind this pre-check.
        String reference = req.reference();
        if (reference != null) {
            var existing = transactions.findFirstByMerchantIdAndReference(m.getId(), reference);
            if (existing.isPresent()) {
                LoyaltyTransaction prior = existing.get();
                if (prior.getType() == TransactionType.REDEMPTION) {
                    // Same logical redemption already happened — replay it, no second debit.
                    return new RedemptionResult(prior.getId(),
                            walletService.mainWallet(u.getPhoneNumber()).getBalance());
                }
                // Reference is already owned by a different transaction type
                // (e.g. a PURCHASE) — refuse rather than silently mis-attribute.
                throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                        "This reference is already used by a non-redemption transaction.");
            }
        }

        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(m.getId());
        t.setUserId(u.getId());
        t.setType(TransactionType.REDEMPTION);
        t.setPointsDelta(req.points().negate());
        // Store the idempotency reference when provided; otherwise fall back to
        // the free-text reason (unchanged behaviour for callers without a key).
        t.setReference(reference != null ? reference : req.reason());

        if (reference != null) {
            // saveAndFlush BEFORE the wallet debit so a concurrent duplicate
            // trips the unique index here (→ 409) rather than after points moved.
            try {
                transactions.saveAndFlush(t);
            } catch (DataIntegrityViolationException dup) {
                throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                        "A redemption with this reference is already being processed.");
            }
        } else {
            transactions.save(t);
        }

        Wallet w = walletService.mainWallet(u.getPhoneNumber());
        BigDecimal balance = walletService.apply(w.getId(), req.points().negate(), t.getId(),
                "redeem:" + (t.getReference() == null ? "n/a" : t.getReference()), tenantId);
        metrics.addPointsRedeemed(req.points());
        return new RedemptionResult(t.getId(), balance);
    }
}
