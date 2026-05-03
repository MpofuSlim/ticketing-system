package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Transactional
public class TransferService {

    private final UserService users;
    private final WalletService walletService;
    private final LoyaltyTransactionRepository transactions;
    private final MerchantRepository merchants;

    public TransferService(UserService users, WalletService walletService,
                           LoyaltyTransactionRepository transactions,
                           MerchantRepository merchants) {
        this.users = users;
        this.walletService = walletService;
        this.transactions = transactions;
        this.merchants = merchants;
    }

    public BigDecimal transfer(UUID tenantId, Dtos.TransferRequest req) {
        if (req.fromUserId().equals(req.toUserId())) {
            throw LoyaltyException.badRequest("SELF_TRANSFER", "cannot transfer to yourself");
        }
        if (req.points() == null || req.points().signum() <= 0) {
            throw LoyaltyException.badRequest("BAD_AMOUNT", "points must be positive");
        }
        var sender = users.require(tenantId, req.fromUserId());
        var recipient = users.require(tenantId, req.toUserId());

        UUID merchantContext = sender.getMerchantId() != null
                ? sender.getMerchantId()
                : merchants.findByTenantId(tenantId).stream().findFirst()
                    .map(m -> m.getId())
                    .orElseThrow(() -> LoyaltyException.badRequest("NO_MERCHANT_CONTEXT",
                            "tenant has no merchant configured"));

        Wallet from = walletService.mainWallet(sender.getId());
        Wallet to = walletService.mainWallet(recipient.getId());

        LoyaltyTransaction debit = new LoyaltyTransaction();
        debit.setTenantId(tenantId);
        debit.setMerchantId(merchantContext);
        debit.setUserId(sender.getId());
        debit.setType(TransactionType.TRANSFER);
        debit.setPointsDelta(req.points().negate());
        debit.setReference(req.reason());
        transactions.save(debit);

        LoyaltyTransaction credit = new LoyaltyTransaction();
        credit.setTenantId(tenantId);
        credit.setMerchantId(merchantContext);
        credit.setUserId(recipient.getId());
        credit.setType(TransactionType.TRANSFER);
        credit.setPointsDelta(req.points());
        credit.setReference(req.reason());
        transactions.save(credit);

        // Lock wallets in canonical UUID order to avoid deadlocks when two
        // transfers between the same pair race in opposite directions.
        if (from.getId().compareTo(to.getId()) < 0) {
            walletService.apply(from.getId(), req.points().negate(), debit.getId(), "transfer-out");
            walletService.apply(to.getId(), req.points(), credit.getId(), "transfer-in");
        } else {
            walletService.apply(to.getId(), req.points(), credit.getId(), "transfer-in");
            walletService.apply(from.getId(), req.points().negate(), debit.getId(), "transfer-out");
        }
        return walletService.totalBalance(sender.getId());
    }
}
