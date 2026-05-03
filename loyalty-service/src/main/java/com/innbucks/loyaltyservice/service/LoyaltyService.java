package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.*;
import com.innbucks.loyaltyservice.entity.LoyaltyAccount;
import com.innbucks.loyaltyservice.entity.LoyaltyRule;
import com.innbucks.loyaltyservice.entity.LoyaltyTransaction;
import com.innbucks.loyaltyservice.exception.InsufficientPointsException;
import com.innbucks.loyaltyservice.repository.LoyaltyAccountRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyRuleRepository;
import com.innbucks.loyaltyservice.repository.LoyaltyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyService {

    private final LoyaltyRuleRepository ruleRepository;
    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository txRepository;

    @Value("${app.loyalty.default-earn-rate:1.0}")
    private BigDecimal defaultEarnRate;

    @Value("${app.loyalty.default-redeem-rate:10.0}")
    private BigDecimal defaultRedeemRate;

    // ==================== RULES ====================

    // Returns the tenant's rule, or a synthetic default rule if none exists.
    // The default is read-only (id=null) so callers can't mutate it; tenants
    // who want to override must POST /loyalty/rules.
    @Transactional(readOnly = true)
    public LoyaltyRule getOrDefaultRule(String tenantId) {
        return ruleRepository.findByTenantId(tenantId)
                .orElseGet(() -> LoyaltyRule.builder()
                        .tenantId(tenantId)
                        .earnRate(defaultEarnRate)
                        .redeemRate(defaultRedeemRate)
                        .active(true)
                        .build());
    }

    @Transactional
    public LoyaltyRule upsertRule(String tenantId, LoyaltyRuleDTO dto) {
        LoyaltyRule rule = ruleRepository.findByTenantId(tenantId).orElseGet(() ->
                LoyaltyRule.builder().tenantId(tenantId).build());
        rule.setEarnRate(dto.getEarnRate());
        rule.setRedeemRate(dto.getRedeemRate());
        rule.setActive(dto.isActive() || rule.getId() == null); // new rules default to active
        LoyaltyRule saved = ruleRepository.save(rule);
        log.info("Loyalty rule upserted tenantId={} earnRate={} redeemRate={} active={}",
                tenantId, saved.getEarnRate(), saved.getRedeemRate(), saved.isActive());
        return saved;
    }

    // ==================== BALANCE ====================

    @Transactional(readOnly = true)
    public BalanceResponseDTO getBalance(String customerId, String tenantId) {
        BigDecimal balance = accountRepository.findByCustomerIdAndTenantId(customerId, tenantId)
                .map(LoyaltyAccount::getBalance)
                .orElse(BigDecimal.ZERO);
        return BalanceResponseDTO.builder()
                .customerId(customerId)
                .tenantId(tenantId)
                .balance(balance)
                .build();
    }

    // ==================== EARN ====================

    @Transactional
    public LoyaltyTransactionDTO earn(EarnRequestDTO req) {
        LoyaltyAccount account = findOrCreateAccount(req.getCustomerId(), req.getTenantId());

        // Idempotency: same (account, EARN, reference) → return the original tx.
        var existing = txRepository.findByAccountIdAndTypeAndReference(
                account.getId(), LoyaltyTransaction.Type.EARN, req.getReference());
        if (existing.isPresent()) {
            log.info("Earn idempotent hit accountId={} reference={}", account.getId(), req.getReference());
            return toDTO(existing.get(), account.getBalance());
        }

        LoyaltyRule rule = getOrDefaultRule(req.getTenantId());
        BigDecimal points = req.getCashAmount().multiply(rule.getEarnRate())
                .setScale(4, RoundingMode.HALF_UP);

        account.setBalance(account.getBalance().add(points));
        accountRepository.save(account);

        LoyaltyTransaction tx = LoyaltyTransaction.builder()
                .accountId(account.getId())
                .type(LoyaltyTransaction.Type.EARN)
                .points(points)
                .dollarAmount(req.getCashAmount())
                .reference(req.getReference())
                .build();
        tx = txRepository.save(tx);

        log.info("Loyalty EARN customerId={} tenantId={} cashAmount={} earnRate={} points={} reference={} balanceAfter={}",
                req.getCustomerId(), req.getTenantId(), req.getCashAmount(), rule.getEarnRate(),
                points, req.getReference(), account.getBalance());

        return toDTO(tx, account.getBalance());
    }

    // ==================== REDEEM ====================

    @Transactional
    public LoyaltyTransactionDTO redeem(RedeemRequestDTO req) {
        LoyaltyAccount account = findOrCreateAccount(req.getCustomerId(), req.getTenantId());

        var existing = txRepository.findByAccountIdAndTypeAndReference(
                account.getId(), LoyaltyTransaction.Type.REDEEM, req.getReference());
        if (existing.isPresent()) {
            log.info("Redeem idempotent hit accountId={} reference={}", account.getId(), req.getReference());
            return toDTO(existing.get(), account.getBalance());
        }

        if (account.getBalance().compareTo(req.getPoints()) < 0) {
            throw new InsufficientPointsException(
                    "Insufficient points: have " + account.getBalance()
                    + ", requested " + req.getPoints());
        }

        account.setBalance(account.getBalance().subtract(req.getPoints()));
        accountRepository.save(account);

        LoyaltyTransaction tx = LoyaltyTransaction.builder()
                .accountId(account.getId())
                .type(LoyaltyTransaction.Type.REDEEM)
                .points(req.getPoints())
                .reference(req.getReference())
                .build();
        tx = txRepository.save(tx);

        log.info("Loyalty REDEEM customerId={} tenantId={} points={} reference={} balanceAfter={}",
                req.getCustomerId(), req.getTenantId(), req.getPoints(),
                req.getReference(), account.getBalance());

        return toDTO(tx, account.getBalance());
    }

    // ==================== HELPERS ====================

    private LoyaltyAccount findOrCreateAccount(String customerId, String tenantId) {
        return accountRepository.findByCustomerIdAndTenantId(customerId, tenantId)
                .orElseGet(() -> accountRepository.save(LoyaltyAccount.builder()
                        .customerId(customerId)
                        .tenantId(tenantId)
                        .balance(BigDecimal.ZERO)
                        .build()));
    }

    private LoyaltyTransactionDTO toDTO(LoyaltyTransaction tx, BigDecimal balanceAfter) {
        return LoyaltyTransactionDTO.builder()
                .id(tx.getId())
                .type(tx.getType())
                .points(tx.getPoints())
                .dollarAmount(tx.getDollarAmount())
                .reference(tx.getReference())
                .balanceAfter(balanceAfter)
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
