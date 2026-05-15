package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.entity.TransactionType;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TransactionService {

    private final LoyaltyTransactionRepository transactions;
    private final WalletRepository wallets;
    private final UserService users;
    private final MerchantService merchants;
    private final WalletService walletService;
    private final RulesEngine rulesEngine;
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;

    public TransactionService(LoyaltyTransactionRepository transactions,
                              WalletRepository wallets,
                              UserService users,
                              MerchantService merchants,
                              WalletService walletService,
                              RulesEngine rulesEngine,
                              com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics) {
        this.transactions = transactions;
        this.wallets = wallets;
        this.users = users;
        this.merchants = merchants;
        this.walletService = walletService;
        this.rulesEngine = rulesEngine;
        this.metrics = metrics;
    }

    public Dtos.TransactionResponse post(UUID tenantId, UUID merchantId, Dtos.TransactionRequest req) {
        Merchant m = merchants.requireMerchant(tenantId, merchantId);
        if (m.getStatus() != Merchant.Status.ACTIVE) {
            throw LoyaltyException.badRequest("MERCHANT_INACTIVE", "merchant is not active; no points will be awarded");
        }
        // Recipient is either an existing LoyaltyUser (userId) or a phone we
        // lazily enrol as PENDING. Exactly one input is required.
        boolean hasUserId = req.userId() != null;
        boolean hasPhone = req.assigneePhone() != null && !req.assigneePhone().isBlank();
        if (hasUserId == hasPhone) {
            throw LoyaltyException.badRequest("RECIPIENT_REQUIRED",
                    "supply exactly one of userId or assigneePhone");
        }
        LoyaltyUser u = hasUserId
                ? users.require(tenantId, req.userId())
                : users.findOrCreatePending(tenantId, req.assigneePhone(), merchantId);
        // Accrual is allowed for PENDING (the whole point of the feature);
        // BLOCKED still rejects so fraud holds stick.
        if (u.getStatus() == LoyaltyUser.Status.BLOCKED) {
            throw LoyaltyException.forbidden("USER_BLOCKED", "user is blocked");
        }

        if (req.reference() != null) {
            transactions.findFirstByMerchantIdAndReference(merchantId, req.reference())
                    .ifPresent(existing -> {
                        throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                                "transaction with this merchant reference already exists");
                    });
        }

        var eval = rulesEngine.evaluate(tenantId, m.getId(), req.type(), req.amount());

        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(m.getId());
        t.setUserId(u.getId());
        t.setType(req.type());
        t.setAmount(req.amount());
        t.setCurrency(req.currency() == null ? m.getCurrency() : req.currency());
        t.setPointsDelta(eval.points());
        t.setRuleId(eval.ruleId());
        t.setCampaignId(eval.campaignId());
        t.setReference(req.reference());
        // saveAndFlush so a unique-constraint violation on (merchant_id, reference)
        // surfaces here as DataIntegrityViolationException rather than at txn
        // commit (where it would bubble up as a 500). The Java pre-check above
        // narrows the window; the DB constraint closes it.
        try {
            transactions.saveAndFlush(t);
        } catch (DataIntegrityViolationException dup) {
            throw LoyaltyException.conflict("DUPLICATE_REFERENCE",
                    "transaction with this merchant reference already exists");
        }

        BigDecimal balance = BigDecimal.ZERO;
        if (eval.points().signum() > 0) {
            Wallet wallet = pickWallet(u.getId(), eval.pocket());
            balance = walletService.apply(wallet.getId(), eval.points(), t.getId(),
                    "earn:" + req.type().name());
        }

        metrics.incTransactionPosted(t.getType().name());
        metrics.addPointsEarned(eval.points());
        return new Dtos.TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                t.getPointsDelta(), balance, t.getRuleId(), t.getCampaignId(),
                t.getReference(), t.getCreatedAt());
    }

    private Wallet pickWallet(UUID userId, String pocket) {
        if (pocket == null) return walletService.mainWallet(userId);
        return wallets.findByUserId(userId).stream()
                .filter(w -> pocket.equals(w.getPocket()))
                .findFirst()
                .orElseGet(() -> walletService.mainWallet(userId));
    }

    public Dtos.TransactionResponse reverse(UUID tenantId, UUID txnId, String reason) {
        LoyaltyTransaction orig = transactions.findById(txnId)
                .orElseThrow(() -> LoyaltyException.notFound("transaction"));
        if (!orig.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "wrong tenant");
        }
        if (orig.getStatus() == LoyaltyTransaction.Status.REVERSED) {
            throw LoyaltyException.conflict("ALREADY_REVERSED", "transaction already reversed");
        }
        orig.setStatus(LoyaltyTransaction.Status.REVERSED);

        LoyaltyTransaction rev = new LoyaltyTransaction();
        rev.setTenantId(tenantId);
        rev.setMerchantId(orig.getMerchantId());
        rev.setUserId(orig.getUserId());
        rev.setType(TransactionType.ADJUSTMENT);
        rev.setAmount(orig.getAmount());
        rev.setCurrency(orig.getCurrency());
        rev.setPointsDelta(orig.getPointsDelta().negate());
        rev.setReversesId(orig.getId());
        rev.setReference(orig.getReference() == null ? null : "REV-" + orig.getReference());
        transactions.save(rev);

        BigDecimal balance = BigDecimal.ZERO;
        if (rev.getPointsDelta().signum() != 0) {
            Wallet w = walletService.mainWallet(orig.getUserId());
            balance = walletService.apply(w.getId(), rev.getPointsDelta(), rev.getId(),
                    "reverse:" + (reason == null ? "n/a" : reason));
        }

        metrics.incTransactionPosted("REVERSAL");
        return new Dtos.TransactionResponse(rev.getId(), rev.getType(), rev.getAmount(),
                rev.getPointsDelta(), balance, rev.getRuleId(), rev.getCampaignId(),
                rev.getReference(), rev.getCreatedAt());
    }

    public Dtos.TransactionResponse adjust(UUID tenantId, UUID userId, UUID merchantId,
                                           BigDecimal points, String reason) {
        merchants.requireMerchant(tenantId, merchantId);
        LoyaltyUser u = users.require(tenantId, userId);
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setTenantId(tenantId);
        t.setMerchantId(merchantId);
        t.setUserId(u.getId());
        t.setType(TransactionType.ADJUSTMENT);
        t.setPointsDelta(points);
        t.setReference(reason);
        transactions.save(t);

        Wallet w = walletService.mainWallet(u.getId());
        BigDecimal balance = walletService.apply(w.getId(), points, t.getId(),
                "adjust:" + (reason == null ? "n/a" : reason));
        metrics.incTransactionPosted("ADJUSTMENT");
        return new Dtos.TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                t.getPointsDelta(), balance, null, null, t.getReference(), t.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<Dtos.TransactionResponse> recentForUser(UUID userId) {
        return transactions.findTop50ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new Dtos.TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                        t.getPointsDelta(), null, t.getRuleId(), t.getCampaignId(),
                        t.getReference(), t.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.TransactionResponse> recentForUser(UUID userId, Pageable pageable) {
        return transactions.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(t -> new Dtos.TransactionResponse(t.getId(), t.getType(), t.getAmount(),
                        t.getPointsDelta(), null, t.getRuleId(), t.getCampaignId(),
                        t.getReference(), t.getCreatedAt()));
    }
}
