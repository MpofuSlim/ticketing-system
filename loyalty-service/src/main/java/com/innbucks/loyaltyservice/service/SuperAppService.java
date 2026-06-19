package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SuperAppService {

    private final WalletService walletService;
    private final VoucherService voucherService;
    private final TransactionService transactionService;
    private final UserService userService;

    public SuperAppService(WalletService walletService, VoucherService voucherService,
                           TransactionService transactionService, UserService userService) {
        this.walletService = walletService;
        this.voucherService = voucherService;
        this.transactionService = transactionService;
        this.userService = userService;
    }

    public Dtos.UserDashboard dashboard(UUID tenantId, UUID userId) {
        // Tenant-scope the lookup. require() throws CROSS_TENANT (403) when the
        // LoyaltyUser belongs to a different tenant than the caller's X-Tenant-Id,
        // so a MERCHANT_ADMIN / SHOP_ADMIN can't read another tenant's dashboard by
        // enumerating UUIDs (SUPER_ADMIN still passes whatever tenant header it
        // likes). Mirrors the gate on GET /loyalty/users/{id}/transactions.
        LoyaltyUser u = userService.require(tenantId, userId);
        // Balance is global per customer (phone); resolve it from the projection.
        return new Dtos.UserDashboard(userId,
                walletService.totalBalance(u.getPhoneNumber()),
                walletService.listForPhone(u.getPhoneNumber()),
                voucherService.activeForUser(userId),
                transactionService.recentForUser(userId));
    }
}
