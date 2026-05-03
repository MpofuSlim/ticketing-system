package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final LoyaltyUserRepository users;
    private final WalletRepository wallets;

    public UserService(LoyaltyUserRepository users, WalletRepository wallets) {
        this.users = users;
        this.wallets = wallets;
    }

    public Dtos.UserResponse create(UUID tenantId, Dtos.UserRequest req) {
        users.findByTenantIdAndPhone(tenantId, req.phone()).ifPresent(u -> {
            throw LoyaltyException.conflict("USER_EXISTS", "user with this phone already exists");
        });
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setMerchantId(req.merchantId());
        u.setPhone(req.phone());
        u.setEmail(req.email());
        u.setFullName(req.fullName());
        u.setNationalId(req.nationalId());
        u.setCountry(req.country());
        if (req.role() != null) u.setRole(req.role());
        users.save(u);

        Wallet main = new Wallet();
        main.setTenantId(tenantId);
        main.setUserId(u.getId());
        main.setLabel("Main");
        main.setType(Wallet.Type.MAIN);
        wallets.save(main);

        return toResponse(u);
    }

    public Dtos.UserResponse deactivate(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.INACTIVE);
        return toResponse(u);
    }

    public Dtos.UserResponse block(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.BLOCKED);
        return toResponse(u);
    }

    @Transactional(readOnly = true)
    public Dtos.UserResponse get(UUID tenantId, UUID userId) {
        return toResponse(require(tenantId, userId));
    }

    public LoyaltyUser require(UUID tenantId, UUID userId) {
        LoyaltyUser u = users.findById(userId)
                .orElseThrow(() -> LoyaltyException.notFound("user"));
        if (!u.getTenantId().equals(tenantId)) {
            throw LoyaltyException.forbidden("CROSS_TENANT", "user belongs to a different tenant");
        }
        return u;
    }

    public static Dtos.UserResponse toResponse(LoyaltyUser u) {
        return new Dtos.UserResponse(u.getId(), u.getTenantId(), u.getPhone(),
                u.getEmail(), u.getFullName(), u.getRole().name(), u.getStatus().name());
    }
}
