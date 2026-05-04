package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SuperAppService {

    private final WalletService walletService;
    private final VoucherService voucherService;
    private final TransactionService transactionService;

    public SuperAppService(WalletService walletService, VoucherService voucherService,
                           TransactionService transactionService) {
        this.walletService = walletService;
        this.voucherService = voucherService;
        this.transactionService = transactionService;
    }

    public Dtos.UserDashboard dashboard(UUID userId) {
        return new Dtos.UserDashboard(userId,
                walletService.totalBalance(userId),
                walletService.listForUser(userId),
                voucherService.activeForUser(userId),
                transactionService.recentForUser(userId));
    }
}
