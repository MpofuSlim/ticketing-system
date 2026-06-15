package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.config.LoyaltyMetrics;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
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

        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(m.getId());
        t.setUserId(u.getId());
        t.setType(TransactionType.REDEMPTION);
        t.setPointsDelta(req.points().negate());
        t.setReference(req.reason());
        transactions.save(t);

        Wallet w = walletService.mainWallet(u.getId());
        BigDecimal balance = walletService.apply(w.getId(), req.points().negate(), t.getId(),
                "redeem:" + (req.reason() == null ? "n/a" : req.reason()));
        metrics.addPointsRedeemed(req.points());
        return new RedemptionResult(t.getId(), balance);
    }
}
