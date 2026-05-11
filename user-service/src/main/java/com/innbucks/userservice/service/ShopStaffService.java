package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Onboards shop staff. Two creation paths with distinct authorization rules:
 *
 * <ul>
 *   <li><b>SHOP_ADMIN</b> by a MERCHANT_ADMIN — caller supplies the target shopId in the body.
 *       The shop is resolved via loyalty-service and must belong to the caller's merchant.</li>
 *   <li><b>SHOP_USER</b> by a SHOP_ADMIN — shopId is pulled from the caller's User row, never
 *       from the body, so a SHOP_ADMIN can only create staff for their own shop.</li>
 * </ul>
 *
 * Both flows stamp the new user with {@code loyaltyMerchantId} and {@code loyaltyShopId} so
 * AuthService can emit those JWT claims at the new user's first login without any cross-service
 * call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShopStaffService {

    /**
     * Default password stamped on every new shop staff member until a notification engine
     * is in place to send them their own onboarding link. They must rotate it via
     * POST /auth/change-password on first login.
     */
    static final String DEFAULT_STAFF_PASSWORD = "#Pass123";

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoyaltyServiceClient loyaltyServiceClient;

    @Transactional
    public UserResponseDTO createShopAdmin(CreateShopAdminDTO req) {
        User caller = requireCaller();
        if (!caller.hasRole(User.Role.MERCHANT_ADMIN)) {
            throw forbidden("Only MERCHANT_ADMIN can create shop admins");
        }
        UUID callerMerchantId = tenantProfileRepository.findByUserId(caller.getId())
                .map(TenantProfile::getLoyaltyMerchantId)
                .orElse(null);
        if (callerMerchantId == null) {
            throw badRequest("Your tenant profile is not bound to a loyalty merchant yet");
        }

        var shop = loyaltyServiceClient.findShop(req.getShopId())
                .orElseThrow(() -> badRequest("Shop not found in loyalty-service"));
        if (shop.merchantId() == null || !UUID.fromString(shop.merchantId()).equals(callerMerchantId)) {
            throw forbidden("Shop does not belong to your merchant");
        }

        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_ADMIN, callerMerchantId, req.getShopId());
        userRepository.save(staff);
        log.info("Created SHOP_ADMIN userId={} email={} shopId={} merchantId={} by={}",
                staff.getId(), staff.getEmail(), req.getShopId(), callerMerchantId, caller.getEmail());
        return UserResponseDTO.from(staff);
    }

    @Transactional
    public UserResponseDTO createShopUser(CreateShopUserDTO req) {
        User caller = requireCaller();
        if (!caller.hasRole(User.Role.SHOP_ADMIN)) {
            throw forbidden("Only SHOP_ADMIN can create shop users");
        }
        UUID callerShopId = caller.getLoyaltyShopId();
        UUID callerMerchantId = caller.getLoyaltyMerchantId();
        if (callerShopId == null || callerMerchantId == null) {
            throw badRequest("Your SHOP_ADMIN account is missing shop scope; contact a merchant admin");
        }

        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_USER, callerMerchantId, callerShopId);
        userRepository.save(staff);
        log.info("Created SHOP_USER userId={} email={} shopId={} by={}",
                staff.getId(), staff.getEmail(), callerShopId, caller.getEmail());
        return UserResponseDTO.from(staff);
    }

    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForCallerShop() {
        User caller = requireCaller();
        UUID shopId = caller.getLoyaltyShopId();
        if (shopId == null) {
            throw badRequest("Caller is not scoped to a shop");
        }
        return userRepository.findByLoyaltyShopId(shopId).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForShop(UUID shopId) {
        User caller = requireCaller();
        UUID callerMerchantId = tenantProfileRepository.findByUserId(caller.getId())
                .map(TenantProfile::getLoyaltyMerchantId)
                .orElse(null);
        if (callerMerchantId == null) {
            throw forbidden("Your tenant profile is not bound to a loyalty merchant yet");
        }
        var shop = loyaltyServiceClient.findShop(shopId)
                .orElseThrow(() -> badRequest("Shop not found in loyalty-service"));
        if (!UUID.fromString(shop.merchantId()).equals(callerMerchantId)) {
            throw forbidden("Shop does not belong to your merchant");
        }
        return userRepository.findByLoyaltyShopId(shopId).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    private User buildStaff(String firstName, String middleName, String lastName,
                            String email, String phone,
                            User.Role role, UUID merchantId, UUID shopId) {
        if (userRepository.existsByEmail(email)) {
            throw badRequest("Email already registered");
        }
        if (userRepository.existsByPhoneNumber(phone)) {
            throw badRequest("Phone number already registered");
        }
        return User.builder()
                .firstName(firstName)
                .middleName(middleName)
                .lastName(lastName)
                .email(email)
                .phoneNumber(phone)
                .password(passwordEncoder.encode(DEFAULT_STAFF_PASSWORD))
                .roles(EnumSet.of(role))
                // Grants the loyalty bundle's microservices (loyalty + payments) on the JWT.
                .defaultServices(new LinkedHashSet<>(List.of(Services.LOYALTY)))
                .active(true)
                .loyaltyMerchantId(merchantId)
                .loyaltyShopId(shopId)
                .build();
    }

    private User requireCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller not found"));
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
