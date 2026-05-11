package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
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
 * Onboards shop staff. Two creation paths:
 *
 * <ul>
 *   <li><b>SHOP_ADMIN</b> by a MERCHANT_ADMIN — caller supplies the target shopId in the body.
 *       The shop is resolved via loyalty-service to pick up its merchantId, which is then
 *       stamped on the new SHOP_ADMIN. The shop must exist; cross-tenant attempts are caught
 *       by loyalty-service's tenant scoping on the lookup endpoint.</li>
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
    private final PasswordEncoder passwordEncoder;
    private final LoyaltyServiceClient loyaltyServiceClient;

    @Transactional
    public UserResponseDTO createShopAdmin(CreateShopAdminDTO req) {
        User caller = requireCaller();
        if (!caller.hasRole(User.Role.MERCHANT_ADMIN)) {
            throw forbidden("Only MERCHANT_ADMIN can create shop admins");
        }
        var shop = loyaltyServiceClient.findShop(req.getShopId())
                .orElseThrow(() -> badRequest("Shop not found in loyalty-service"));
        if (shop.merchantId() == null || shop.merchantId().isBlank()) {
            throw badRequest("Shop has no merchant binding");
        }
        UUID merchantId = UUID.fromString(shop.merchantId());

        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_ADMIN, merchantId, req.getShopId());
        userRepository.save(staff);
        log.info("Created SHOP_ADMIN userId={} email={} shopId={} merchantId={} by={}",
                staff.getId(), staff.getEmail(), req.getShopId(), merchantId, caller.getEmail());
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

    /**
     * Role-aware "my staff" view backing GET /admin/shop-staff/mine:
     * <ul>
     *   <li>SHOP_ADMIN — lists every user at the caller's shop (JWT-stamped
     *       {@code loyaltyShopId} on the User row). The {@code merchantId}
     *       argument is ignored.</li>
     *   <li>MERCHANT_ADMIN — lists every user under the supplied merchant.
     *       Missing {@code merchantId} returns 400 since MERCHANT_ADMINs have
     *       no merchant claim on their token to fall back on.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForCaller(UUID merchantId) {
        User caller = requireCaller();
        if (caller.hasRole(User.Role.SHOP_ADMIN)) {
            UUID shopId = caller.getLoyaltyShopId();
            if (shopId == null) {
                throw badRequest("Caller is not scoped to a shop");
            }
            return userRepository.findByLoyaltyShopId(shopId).stream()
                    .map(UserResponseDTO::from)
                    .toList();
        }
        if (caller.hasRole(User.Role.MERCHANT_ADMIN)) {
            if (merchantId == null) {
                throw badRequest("merchantId query parameter is required when the caller is a MERCHANT_ADMIN");
            }
            return userRepository.findByLoyaltyMerchantId(merchantId).stream()
                    .map(UserResponseDTO::from)
                    .toList();
        }
        throw forbidden("Caller has no shop or merchant scope");
    }

    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForShop(UUID shopId) {
        requireCaller();
        // The MERCHANT_ADMIN role gate on the controller plus the shop's existence in
        // loyalty-service are the protection here — we no longer cross-check caller's
        // merchant binding because that data path has been removed.
        loyaltyServiceClient.findShop(shopId)
                .orElseThrow(() -> badRequest("Shop not found in loyalty-service"));
        return userRepository.findByLoyaltyShopId(shopId).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    /**
     * Returns every SHOP_ADMIN and SHOP_USER under the given merchant — i.e. the
     * full headcount across all shops belonging to that merchant. Used by the
     * MERCHANT_ADMIN dashboard so they can oversee every staff member their shop
     * admins manage in one call.
     */
    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForMerchant(UUID merchantId) {
        requireCaller();
        return userRepository.findByLoyaltyMerchantId(merchantId).stream()
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
