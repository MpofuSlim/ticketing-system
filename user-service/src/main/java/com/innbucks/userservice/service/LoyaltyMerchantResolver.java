package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.TenantProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a MERCHANT_ADMIN's loyalty merchant binding.
 *
 * <p>The bound value lives on {@link TenantProfile#getLoyaltyMerchantId()}. When it's null we
 * ask loyalty-service to look it up by {@code Merchant.admin_email} (stamped when the user
 * created their merchant) and cache the answer back onto the TenantProfile so subsequent
 * calls skip the cross-service round-trip.
 *
 * <p>Shared by AuthService (so the JWT claim is populated at login) and ShopStaffService
 * (so creating shop admins works even before the user logs out/in after their first
 * merchant creation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyMerchantResolver {

    private final TenantProfileRepository tenantProfileRepository;
    private final LoyaltyServiceClient loyaltyServiceClient;

    /**
     * Returns the merchantId bound to the given user. Falls back to a loyalty-service lookup
     * by email if the TenantProfile cache is unpopulated. Returns empty when the user has no
     * TenantProfile, no email to look up by, or when loyalty-service returns no match.
     */
    @Transactional
    public Optional<UUID> resolveForUser(User user) {
        if (user == null) return Optional.empty();
        Optional<TenantProfile> profileOpt = tenantProfileRepository.findByUserId(user.getId());
        if (profileOpt.isEmpty()) return Optional.empty();
        TenantProfile profile = profileOpt.get();
        if (profile.getLoyaltyMerchantId() != null) {
            return Optional.of(profile.getLoyaltyMerchantId());
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) return Optional.empty();

        Optional<UUID> looked = loyaltyServiceClient.findMerchantIdByAdminEmail(user.getEmail());
        if (looked.isEmpty()) return Optional.empty();
        UUID resolved = looked.get();
        profile.setLoyaltyMerchantId(resolved);
        tenantProfileRepository.save(profile);
        log.info("Resolved loyalty merchant via lookup userId={} email={} merchantId={}",
                user.getId(), user.getEmail(), resolved);
        return Optional.of(resolved);
    }
}
