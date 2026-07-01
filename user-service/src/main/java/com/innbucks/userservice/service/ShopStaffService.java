package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
 *       The shop is resolved via loyalty-service to pick up its merchantId, which MUST match the
 *       caller's own {@code loyaltyMerchantId} (a MERCHANT_ADMIN can only provision staff into
 *       their own merchant's shops); the merchantId is then stamped on the new SHOP_ADMIN.</li>
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoyaltyServiceClient loyaltyServiceClient;
    private final ApplicationEventPublisher eventPublisher;

    /** Deployment country pin (ISO 3166-1 alpha-2). Shop staff are anchored
     *  to this cell — home_country is the deployment country, not derived
     *  from the staff member's MSISDN. Set via INNBUCKS_COUNTRY env var. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry;

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
        // Scope to the caller's own merchant: a MERCHANT_ADMIN must not be able
        // to provision a SHOP_ADMIN into another merchant's shop (cross-tenant
        // privilege escalation) -> 403 "Shop does not belong to your merchant".
        requireCallerOwnsShop(caller, shop.merchantId());

        String tempPassword = TemporaryPasswordGenerator.generate();
        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_ADMIN, merchantId, req.getShopId(), tempPassword);
        userRepository.save(staff);
        log.info("Created SHOP_ADMIN userId={} email={} shopId={} merchantId={} by={}",
                staff.getId(), staff.getEmail(), req.getShopId(), merchantId, caller.getEmail());
        // Deliver credentials OFF the request thread (email -> SMS -> WhatsApp,
        // best-effort, AFTER_COMMIT) via the same pipeline UserAdminService uses.
        // The old inline send blocked the response on the notification gateway
        // (~30s email read timeout), so a slow/hung gateway timed out the client
        // even though the account had already committed.
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                staff.getId(), staff.getFirstName(), staff.getEmail(), staff.getPhoneNumber(),
                tempPassword, CredentialDeliveryRequested.Reason.ONBOARDING));
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

        String tempPassword = TemporaryPasswordGenerator.generate();
        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_USER, callerMerchantId, callerShopId, tempPassword);
        userRepository.save(staff);
        log.info("Created SHOP_USER userId={} email={} shopId={} by={}",
                staff.getId(), staff.getEmail(), callerShopId, caller.getEmail());
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                staff.getId(), staff.getFirstName(), staff.getEmail(), staff.getPhoneNumber(),
                tempPassword, CredentialDeliveryRequested.Reason.ONBOARDING));
        return UserResponseDTO.from(staff);
    }

    @Transactional(readOnly = true)
    public List<UserResponseDTO> listForCallerShop() {
        User caller = requireCaller();
        // MERCHANT_ADMIN: "my staff" spans every shop under every merchant they
        // administer. Their JWT/row carries no shop (or merchant) scope, so
        // resolve their merchants from loyalty-service and return the full
        // headcount across them. Empty set => they own nothing yet => empty list.
        if (caller.hasRole(User.Role.MERCHANT_ADMIN)) {
            java.util.Set<UUID> merchantIds = resolveCallerMerchantIds(caller);
            if (merchantIds.isEmpty()) {
                return List.of();
            }
            return merchantIds.stream()
                    .flatMap(mid -> userRepository.findByLoyaltyMerchantId(mid).stream())
                    .map(UserResponseDTO::from)
                    .toList();
        }
        // SHOP_ADMIN (and any other shop-scoped caller): staff at their own shop.
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
        // Scope to the caller's own merchant: a MERCHANT_ADMIN may only list the
        // staff of shops belonging to THEIR merchant. Resolve the shop, then
        // require its merchant to match the caller's — 403 otherwise (closes the
        // cross-merchant staff-PII leak).
        var shop = loyaltyServiceClient.findShop(shopId)
                .orElseThrow(() -> badRequest("Shop not found in loyalty-service"));
        requireCallerOwnsShop(caller, shop.merchantId());
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
        User caller = requireCaller();
        // Scope to the caller's own merchant(s): a MERCHANT_ADMIN may only list
        // the headcount of a merchant THEY administer, not enumerate any
        // merchant's staff PII by id -> 403 for a merchant they don't own.
        if (!resolveCallerMerchantIds(caller).contains(merchantId)) {
            throw forbidden("Merchant does not belong to your account");
        }
        return userRepository.findByLoyaltyMerchantId(merchantId).stream()
                .map(UserResponseDTO::from)
                .toList();
    }

    /**
     * Re-issues a temp password for a shop-staff member and re-delivers it via
     * the same email → SMS pipeline used at onboarding. Use case: the original
     * onboarding notification never reached them and they can't log in.
     *
     * <p>Authorization is role-aware to mirror the creation hierarchy:
     * <ul>
     *   <li>A {@link User.Role#SHOP_ADMIN} may reset a {@link User.Role#SHOP_USER}
     *       at <b>their own shop</b> (matching {@code loyaltyShopId}).</li>
     *   <li>A {@link User.Role#MERCHANT_ADMIN} may reset either a SHOP_ADMIN or a
     *       SHOP_USER at <b>their own merchant</b> (matching {@code loyaltyMerchantId}).</li>
     * </ul>
     *
     * <p>Out-of-scope targets return 404 (don't disclose existence of staff at
     * other shops / merchants). SUPER_ADMIN intentionally is NOT routed through
     * here — they use {@code POST /admin/users/{id}/reset-temp-password} which
     * handles every user type uniformly.
     *
     * <p>Sets {@code mustChangePassword=true}; does NOT bump {@code tokenVersion}
     * — matching the SUPER_ADMIN reset path. If you need to terminate
     * outstanding sessions too, deactivate the user first via a future disable
     * endpoint. Best-effort delivery: the password rotation commits even if
     * email and SMS both fail (re-run the reset to retry notification).
     */
    @Transactional
    public UserResponseDTO resetTemporaryPassword(UUID userUuid) {
        User caller = requireCaller();
        User target = userRepository.findByUserUuid(userUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found"));
        if (!callerMayResetTarget(caller, target)) {
            // 404 not 403 — same disclosure rule as TeamMemberService.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        String tempPassword = TemporaryPasswordGenerator.generate();
        target.setPassword(passwordEncoder.encode(tempPassword));
        target.setMustChangePassword(true);
        userRepository.save(target);
        log.info("Reset shop-staff temp password targetUserUuid={} targetRole={} by={}",
                target.getUserUuid(),
                target.hasRole(User.Role.SHOP_ADMIN) ? "SHOP_ADMIN" : "SHOP_USER",
                caller.getEmail());

        // Off-thread delivery (same event pipeline as onboarding) so a hung
        // gateway can't stall the reset response after the password is rotated.
        eventPublisher.publishEvent(new CredentialDeliveryRequested(
                target.getId(), target.getFirstName(), target.getEmail(), target.getPhoneNumber(),
                tempPassword, CredentialDeliveryRequested.Reason.RESET));
        return UserResponseDTO.from(target);
    }

    /**
     * Encodes the creator-resets-subordinate hierarchy. Returns false (and the
     * caller surfaces 404) for cross-shop, cross-merchant, or wrong-role
     * combinations. Top-level callers (SUPER_ADMIN) intentionally fall through
     * to false here — they go via {@code /admin/users/{id}/reset-temp-password}
     * instead.
     */
    private boolean callerMayResetTarget(User caller, User target) {
        if (caller.hasRole(User.Role.MERCHANT_ADMIN)) {
            if (!target.hasRole(User.Role.SHOP_ADMIN) && !target.hasRole(User.Role.SHOP_USER)) {
                return false;
            }
            UUID callerMerchant = caller.getLoyaltyMerchantId();
            UUID targetMerchant = target.getLoyaltyMerchantId();
            return callerMerchant != null && callerMerchant.equals(targetMerchant);
        }
        if (caller.hasRole(User.Role.SHOP_ADMIN)) {
            if (!target.hasRole(User.Role.SHOP_USER)) {
                // A SHOP_ADMIN can't reset a peer SHOP_ADMIN — only the
                // MERCHANT_ADMIN above them can. Reset by the merchant
                // admin keeps shop authority unambiguous.
                return false;
            }
            UUID callerShop = caller.getLoyaltyShopId();
            UUID targetShop = target.getLoyaltyShopId();
            return callerShop != null && callerShop.equals(targetShop);
        }
        return false;
    }

    private User buildStaff(String firstName, String middleName, String lastName,
                            String email, String phone,
                            User.Role role, UUID merchantId, UUID shopId, String tempPassword) {
        if (userRepository.existsByEmail(email)) {
            throw badRequest("Email already registered");
        }
        // Step 4: composite (phone, home_country) check matching the new
        // uk_users_phone_country constraint. Staff are anchored to this cell.
        if (userRepository.existsByPhoneNumberAndHomeCountry(phone, deploymentCountry)) {
            throw badRequest("Phone number already registered");
        }
        return User.builder()
                .firstName(firstName)
                .middleName(middleName)
                .lastName(lastName)
                .email(email)
                .phoneNumber(phone)
                .homeCountry(deploymentCountry)
                .password(passwordEncoder.encode(tempPassword))
                .roles(EnumSet.of(role))
                // Grants the loyalty bundle's microservices (loyalty + payments) on the JWT.
                .defaultServices(new LinkedHashSet<>(List.of(Services.LOYALTY)))
                .active(true)
                // Created by a MERCHANT_ADMIN / SHOP_ADMIN — no SUPER_ADMIN
                // approval step, so they are approved on creation. Set it
                // explicitly so login's pending-approval check never treats shop
                // staff as a pending registration.
                .approved(true)
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

    /**
     * A MERCHANT_ADMIN may only act on shops belonging to a merchant THEY
     * administer. Resolves the caller's merchant set (see
     * {@link #resolveCallerMerchantIds}) and requires the shop's merchantId to be
     * in it; otherwise 403 "Shop does not belong to your merchant".
     */
    private void requireCallerOwnsShop(User caller, String shopMerchantId) {
        if (shopMerchantId == null || shopMerchantId.isBlank()) {
            throw forbidden("Shop does not belong to your merchant");
        }
        UUID shopMerchant;
        try {
            shopMerchant = UUID.fromString(shopMerchantId);
        } catch (IllegalArgumentException e) {
            throw forbidden("Shop does not belong to your merchant");
        }
        if (!resolveCallerMerchantIds(caller).contains(shopMerchant)) {
            throw forbidden("Shop does not belong to your merchant");
        }
    }

    /**
     * The set of merchants a caller administers. A MERCHANT_ADMIN carries no
     * merchantId on their JWT or User row (they may run several merchants), so we
     * resolve the set from loyalty-service by their admin email — the same
     * binding loyalty stamps on every merchant at creation
     * ({@code merchant.adminEmail}). Any {@code loyaltyMerchantId} on the row
     * (defensive; normally only shop staff carry one) is folded in too. An empty
     * set means the caller owns nothing, so every ownership check fails closed.
     */
    private java.util.Set<UUID> resolveCallerMerchantIds(User caller) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        if (caller.getLoyaltyMerchantId() != null) {
            ids.add(caller.getLoyaltyMerchantId());
        }
        String email = caller.getEmail();
        if (email != null && !email.isBlank()) {
            ids.addAll(loyaltyServiceClient.merchantIdsForAdmin(email));
        }
        return ids;
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
