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

import java.util.List;
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
    private final com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics;

    public UserService(LoyaltyUserRepository users,
                       WalletRepository wallets,
                       UserServiceClient userServiceClient,
                       com.innbucks.loyaltyservice.config.LoyaltyMetrics metrics) {
        this.users = users;
        this.wallets = wallets;
        this.userServiceClient = userServiceClient;
        this.metrics = metrics;
    }

    // Idempotent enrolment: returns the existing LoyaltyUser for the
    // (tenant, phone) pair, or creates one after validating the phone
    // number resolves to a real customer in user-service. Use this when the
    // recipient is known to be registered (e.g. explicit enrolment flow).
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
        return createWithWallet(tenantId, phoneNumber, merchantId, LoyaltyUser.Status.ACTIVE);
    }

    /**
     * Phone-keyed wallet entry-point: returns the existing LoyaltyUser, or
     * creates a fresh projection when the recipient hasn't been seen here
     * before. Used by issuance / transfer / shop-checkout flows that want to
     * credit a phone whether or not user-service has heard of it.
     *
     * <p>Status of a newly-created row depends on what user-service knows:
     * <ul>
     *   <li><b>ACTIVE</b> — user-service confirms the phone resolves to a
     *       registered customer. Skips the PENDING→ACTIVE webhook handoff
     *       entirely, so the customer can both accrue and spend on first
     *       contact with loyalty (the bug that motivated this change: a
     *       customer registered before their first loyalty interaction was
     *       getting stuck PENDING because the promotion webhook fires at
     *       registration time, and there was no registration left to trigger
     *       it).</li>
     *   <li><b>PENDING</b> — user-service has no customer for this phone, OR
     *       the lookup failed (degraded mode: we still create the projection
     *       so accrual can land; the customer just can't spend until they
     *       register and the promote webhook flips them).</li>
     * </ul>
     *
     * <p>Spend-side gates (redemption, outgoing transfer) consult
     * {@link #requireSpendable(LoyaltyUser)} downstream, so a PENDING user
     * still receives points but can't spend them — unchanged.
     */
    public LoyaltyUser findOrCreatePending(UUID tenantId, String phoneNumber, UUID merchantId) {
        Optional<LoyaltyUser> existing = users.findByTenantIdAndPhoneNumber(tenantId, phoneNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Probe user-service. A registered phone means the customer can spend
        // immediately — there's no PENDING state to recover from. An empty
        // response (unknown phone) or a lookup failure both fall through to
        // PENDING so accrual still works even if user-service is degraded.
        LoyaltyUser.Status status = userServiceClient.getCustomerTier(phoneNumber).isPresent()
                ? LoyaltyUser.Status.ACTIVE
                : LoyaltyUser.Status.PENDING;
        return createWithWallet(tenantId, phoneNumber, merchantId, status);
    }

    private LoyaltyUser createWithWallet(UUID tenantId, String phoneNumber, UUID merchantId,
                                         LoyaltyUser.Status status) {
        LoyaltyUser u = new LoyaltyUser();
        u.setTenantId(tenantId);
        u.setMerchantId(merchantId);
        u.setPhoneNumber(phoneNumber);
        u.setStatus(status);
        users.save(u);

        Wallet main = new Wallet();
        main.setTenantId(tenantId);
        main.setUserId(u.getId());
        main.setLabel("Main");
        main.setType(Wallet.Type.MAIN);
        wallets.save(main);

        return u;
    }

    /**
     * Throws if the user can't perform a *spending* action right now. Use this
     * on every redemption / outgoing-transfer path so PENDING (not yet
     * registered) and BLOCKED (fraud) accounts can accrue but not spend.
     */
    public void requireSpendable(LoyaltyUser u) {
        switch (u.getStatus()) {
            case ACTIVE -> { /* ok */ }
            case PENDING -> throw LoyaltyException.forbidden("USER_PENDING",
                    "user has not completed registration; balance can be received but not spent");
            case BLOCKED -> throw LoyaltyException.forbidden("USER_BLOCKED", "user is blocked");
            case INACTIVE -> throw LoyaltyException.forbidden("USER_INACTIVE", "user is inactive");
        }
    }

    /**
     * Throws 403 NOT_WALLET_OWNER unless the caller is acting on their own
     * LoyaltyUser, OR holds an admin role (SUPER_ADMIN / MERCHANT_ADMIN /
     * SHOP_ADMIN) that's explicitly allowed to act on behalf of another user
     * (customer-support reversals, merchant-ops actions, etc.).
     *
     * <p>Used by every endpoint that accepts a {@code userId} from the URL or
     * body — without this check a logged-in CUSTOMER could drain or read any
     * other user's data simply by guessing or harvesting their UUID.
     */
    public void requireCallerOwnsOrIsAdmin(LoyaltyUser target) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth != null) {
            for (var ga : auth.getAuthorities()) {
                String role = ga.getAuthority();
                if ("ROLE_SUPER_ADMIN".equals(role)
                        || "ROLE_MERCHANT_ADMIN".equals(role)
                        || "ROLE_SHOP_ADMIN".equals(role)) {
                    return; // admin acting on behalf is allowed
                }
            }
        }
        String callerPhone = com.innbucks.loyaltyservice.security.CallerDetails.currentPhoneNumber();
        if (callerPhone == null || !callerPhone.equals(target.getPhoneNumber())) {
            throw LoyaltyException.forbidden("NOT_WALLET_OWNER",
                    "you can only act on your own loyalty account");
        }
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

    /**
     * Called by user-service via the {@code /loyalty/internal/users/promote}
     * webhook the moment a phone completes registration. Flips every
     * {@link LoyaltyUser.Status#PENDING} row matching that phone — across all
     * tenants — to {@link LoyaltyUser.Status#ACTIVE} so the receiver can now
     * spend whatever accrued while they were unregistered.
     *
     * <p>Idempotent. Already-ACTIVE rows are left alone; BLOCKED/INACTIVE rows
     * stay where they are (registration doesn't unblock fraud holds).
     *
     * @return count of rows promoted in this call.
     */
    public int promoteByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw LoyaltyException.badRequest("BAD_PHONE", "phoneNumber required");
        }
        List<LoyaltyUser> matches = users.findByPhoneNumber(phoneNumber);
        int promoted = 0;
        for (LoyaltyUser u : matches) {
            if (u.getStatus() == LoyaltyUser.Status.PENDING) {
                u.setStatus(LoyaltyUser.Status.ACTIVE);
                promoted++;
            }
        }
        metrics.incPendingPromoted(promoted);
        return promoted;
    }

    public static Dtos.UserResponse toResponse(LoyaltyUser u) {
        return new Dtos.UserResponse(u.getId(), u.getTenantId(), u.getPhoneNumber(),
                u.getRole().name(), u.getStatus().name());
    }
}
