package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.PointsLedger;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.PointsLedgerRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WalletService {

    private final WalletRepository wallets;
    private final PointsLedgerRepository ledger;
    private final OradianSyncService oradianSyncService;

    public WalletService(WalletRepository wallets, PointsLedgerRepository ledger,
                         OradianSyncService oradianSyncService) {
        this.wallets = wallets;
        this.ledger = ledger;
        this.oradianSyncService = oradianSyncService;
    }

    @Transactional(readOnly = true)
    public List<Dtos.WalletResponse> listForUser(UUID userId) {
        return wallets.findByUserId(userId).stream().map(WalletService::toResponse).toList();
    }

    public Dtos.WalletResponse createSubWallet(UUID tenantId, UUID userId, Dtos.SubWalletRequest req) {
        Wallet w = new Wallet();
        w.setTenantId(tenantId);
        w.setUserId(userId);
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

    public Wallet mainWallet(UUID userId) {
        return wallets.findFirstByUserIdAndType(userId, Wallet.Type.MAIN)
                .orElseThrow(() -> LoyaltyException.notFound("main wallet"));
    }

    /**
     * Apply a delta atomically to a wallet using a row-level pessimistic lock.
     * Records a ledger entry. Caller must ensure the wallet belongs to the
     * expected tenant. Negative balances are rejected at the DB level via the
     * check constraint; we surface a domain error first when known.
     *
     * <p>When {@code loyalty.oradian-sync.enabled=true}, the delta is also
     * mirrored to the customer's LPW account on Oradian BEFORE the local
     * wallet mutation commits. An upstream failure throws
     * {@code ORADIAN_SYNC_FAILED} → the surrounding {@code @Transactional}
     * rolls back → the customer's earn / spend doesn't appear locally
     * either. See {@link OradianSyncService} for the full sync-first
     * model and how PENDING users / missing LPW accounts skip the sync.
     */
    public BigDecimal apply(UUID walletId, BigDecimal delta, UUID transactionId, String reason) {
        Wallet w = wallets.lockById(walletId)
                .orElseThrow(() -> LoyaltyException.notFound("wallet"));
        if (delta.signum() < 0 && w.getLockedUntil() != null
                && LocalDate.now().isBefore(w.getLockedUntil())) {
            throw LoyaltyException.badRequest("WALLET_LOCKED", "wallet is locked until " + w.getLockedUntil());
        }
        BigDecimal newBalance = w.getBalance().add(delta);
        if (newBalance.signum() < 0) {
            throw LoyaltyException.badRequest("INSUFFICIENT_FUNDS", "wallet balance would go negative");
        }

        // Sync to Oradian BEFORE mutating local state. On failure this
        // throws and the @Transactional rolls back the lock + any
        // partial wallet edits (none yet — we haven't set the new
        // balance). FAILED audit rows persist via REQUIRES_NEW inside
        // OradianSyncService. No-op when the feature flag is off or
        // when the wallet's user is PENDING / has no LPW account.
        oradianSyncService.syncDelta(w, delta, transactionId, reason);

        w.setBalance(newBalance);

        PointsLedger entry = new PointsLedger();
        entry.setTenantId(w.getTenantId());
        entry.setWalletId(walletId);
        entry.setTransactionId(transactionId);
        entry.setDelta(delta);
        entry.setBalanceAfter(newBalance);
        entry.setReason(reason);
        ledger.save(entry);
        return newBalance;
    }

    @Transactional(readOnly = true)
    public BigDecimal totalBalance(UUID userId) {
        return wallets.findByUserId(userId).stream()
                .map(Wallet::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Dtos.WalletResponse toResponse(Wallet w) {
        return new Dtos.WalletResponse(w.getId(), w.getUserId(), w.getLabel(),
                w.getType().name(), w.getPocket(), w.getBalance(), w.getLockedUntil());
    }
}
