package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.PointsLedger;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Wallets are GLOBAL per customer (keyed by the platform-stable phone number),
 * not per tenant: a customer has one MAIN wallet across the whole super-app, so
 * points earned at any tenant are spendable at any tenant. The per-tenant
 * {@code LoyaltyUser} projection still carries role/status/merchant attribution
 * and the per-merchant ledger, but the spendable balance lives in the one
 * wallet resolved here by phone.
 */
@Service
@Transactional
public class WalletService {

    private final WalletRepository wallets;
    private final PointsLedgerRepository ledger;

    public WalletService(WalletRepository wallets, PointsLedgerRepository ledger) {
        this.wallets = wallets;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public List<Dtos.WalletResponse> listForPhone(String phoneNumber) {
        return wallets.findByPhoneNumber(phoneNumber).stream().map(WalletService::toResponse).toList();
    }

    public Dtos.WalletResponse createSubWallet(String phoneNumber, Dtos.SubWalletRequest req) {
        Wallet w = new Wallet();
        w.setPhoneNumber(phoneNumber);
        w.setLabel(req.label());
        w.setPocket(req.pocket());
        if (req.type() != null) {
            try { w.setType(Wallet.Type.valueOf(req.type())); }
            catch (IllegalArgumentException e) { throw LoyaltyException.badRequest("BAD_TYPE", "invalid wallet type"); }
        } else {
            w.setType(Wallet.Type.SUB);
        }
        if (w.getType() == Wallet.Type.MAIN) {
            throw LoyaltyException.badRequest("BAD_TYPE", "MAIN wallet is created automatically");
        }
        w.setLockedUntil(req.lockedUntil());
        wallets.save(w);
        return toResponse(w);
    }

    /**
     * The customer's single global MAIN wallet, created on first use. Race-safe:
     * a concurrent first-use that loses the unique-index race re-reads the
     * winner's row instead of creating a duplicate.
     */
    public Wallet mainWallet(String phoneNumber) {
        return wallets.findFirstByPhoneNumberAndType(phoneNumber, Wallet.Type.MAIN)
                .orElseGet(() -> createMainWallet(phoneNumber));
    }

    private Wallet createMainWallet(String phoneNumber) {
        Wallet w = new Wallet();
        w.setPhoneNumber(phoneNumber);
        w.setLabel("Main");
        w.setType(Wallet.Type.MAIN);
        try {
            return wallets.saveAndFlush(w);
        } catch (DataIntegrityViolationException race) {
            // Another thread created the customer's MAIN wallet first (uk_wallet_main).
            return wallets.findFirstByPhoneNumberAndType(phoneNumber, Wallet.Type.MAIN)
                    .orElseThrow(() -> race);
        }
    }

    /**
     * Apply a delta atomically to a wallet using a row-level pessimistic lock and
     * record a ledger entry. {@code ledgerTenantId} is the tenant where the
     * activity originated (the global wallet itself is tenant-less), preserving
     * per-tenant attribution on the audit ledger.
     */
    public BigDecimal apply(UUID walletId, BigDecimal delta, UUID transactionId, String reason, UUID ledgerTenantId) {
        Wallet w = wallets.lockById(walletId)
                .orElseThrow(() -> LoyaltyException.notFound("wallet"));
        if (delta.signum() < 0 && w.getLockedUntil() != null
                && LocalDate.now(ZoneOffset.UTC).isBefore(w.getLockedUntil())) {
            throw LoyaltyException.badRequest("WALLET_LOCKED", "wallet is locked until " + w.getLockedUntil());
        }
        BigDecimal newBalance = w.getBalance().add(delta);
        if (newBalance.signum() < 0) {
            throw LoyaltyException.badRequest("INSUFFICIENT_FUNDS", "You don't have enough loyalty points for this.");
        }
        w.setBalance(newBalance);

        PointsLedger entry = new PointsLedger();
        entry.setTenantId(ledgerTenantId);
        entry.setWalletId(walletId);
        entry.setTransactionId(transactionId);
        entry.setDelta(delta);
        entry.setBalanceAfter(newBalance);
        entry.setReason(reason);
        ledger.save(entry);
        return newBalance;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalBalance(String phoneNumber) {
        return wallets.findByPhoneNumber(phoneNumber).stream()
                .map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Dtos.WalletResponse toResponse(Wallet w) {
        return new Dtos.WalletResponse(w.getId(), w.getUserId(), w.getLabel(),
                w.getType().name(), w.getPocket(), w.getBalance(), w.getLockedUntil());
    }
}
