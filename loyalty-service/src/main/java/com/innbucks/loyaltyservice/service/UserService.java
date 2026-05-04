package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.CustomerTierResponseDTO;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.LoyaltyUser;
import com.innbucks.loyaltyservice.entity.Wallet;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.LoyaltyUserRepository;
import com.innbucks.loyaltyservice.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// Manages the loyalty-side projection of a user. Identity (name, email,
// nationalId) lives in user-service — this service only stores foreign
// references plus loyalty-specific state. There is intentionally no public
// "create user" endpoint: callers (TransactionService, VoucherService, QR
// flows) reach a user via {@link #findOrEnrol(UUID, String, UUID)} which
// validates the phone number against user-service first.
@Service
@Transactional
public class UserService {

    private final LoyaltyUserRepository users;
    private final WalletRepository wallets;
    private final UserServiceClient userServiceClient;

    public UserService(LoyaltyUserRepository users,
                       WalletRepository wallets,
                       UserServiceClient userServiceClient) {
        this.users = users;
        this.wallets = wallets;
        this.userServiceClient = userServiceClient;
    }

    // Idempotent enrolment: returns the existing LoyaltyUser for the
    // (tenant, phone) pair, or creates one after validating the phone
    // number resolves to a real customer in user-service.
    public LoyaltyUser findOrEnrol(UUID tenantId, String phoneNumber, UUID merchantId) {
        Optional<LoyaltyUser> existing = users.findByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<CustomerTierResponseDTO> verified = userServiceClient.getCustomerTier(phoneNumber);
        if (verified.isEmpty()) {
            throw LoyaltyException.notFound(
                    "user-service has no customer with phone " + phoneNumber);
        }
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setMerchantId(merchantId);
        u.setPhoneNumber(phoneNumber);
        users.save(u);

        Wallet main = new Wallet();
        main.setTenantId(tenantId);
        main.setUserId(u.getId());
        main.setLabel("Main");
        main.setType(Wallet.Type.MAIN);
        wallets.save(main);

        return u;
    }

    // Internal lifecycle hooks used by FraudService and admin flows. These
    // affect the LoyaltyUser's status within the loyalty program only — they
    // do NOT change the user's account state in user-service.
    public LoyaltyUser deactivate(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.INACTIVE);
        return u;
    }

    public LoyaltyUser block(UUID tenantId, UUID userId) {
        LoyaltyUser u = require(tenantId, userId);
        u.setStatus(LoyaltyUser.Status.BLOCKED);
        return u;
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
        return new Dtos.UserResponse(u.getId(), u.getTenantId(), u.getPhoneNumber(),
                u.getRole().name(), u.getStatus().name());
    }
}
