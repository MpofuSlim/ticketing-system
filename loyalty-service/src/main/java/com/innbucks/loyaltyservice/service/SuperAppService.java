package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SuperAppService {

    private final WalletService walletService;
    private final VoucherService voucherService;
    private final TransactionService transactionService;
    private final LoyaltyUserRepository users;

    public SuperAppService(WalletService walletService, VoucherService voucherService,
                           TransactionService transactionService, LoyaltyUserRepository users) {
        this.walletService = walletService;
        this.voucherService = voucherService;
        this.transactionService = transactionService;
        this.users = users;
    }

    public Dtos.UserDashboard dashboard(UUID userId) {
        // Balance is global per customer (phone); resolve it from the projection.
        LoyaltyUser u = users.findById(userId)
                .orElseThrow(() -> LoyaltyException.notFound("user"));
        return new Dtos.UserDashboard(userId,
                walletService.totalBalance(u.getPhoneNumber()),
                walletService.listForPhone(u.getPhoneNumber()),
                voucherService.activeForUser(userId),
                transactionService.recentForUser(userId));
    }
}
