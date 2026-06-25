package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.TemporaryPasswordGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final EmailNotificationClient emailNotificationClient;
    private final SmsNotificationClient smsNotificationClient;

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
        notifyOnboarding(staff, tempPassword);
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
        notifyOnboarding(staff, tempPassword);
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
        // Scope to the caller's own merchant: a MERCHANT_ADMIN may only list the
        // headcount of THEIR merchant, not enumerate any merchant's staff PII by
        // id -> 403 for a foreign merchantId.
        UUID callerMerchant = caller.getLoyaltyMerchantId();
        if (callerMerchant == null || !callerMerchant.equals(merchantId)) {
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

        notifyPasswordReset(target, tempPassword);
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

    private void notifyPasswordReset(User staff, String tempPassword) {
        String roleLabel = staff.hasRole(User.Role.SHOP_ADMIN) ? "Shop Administrator" : "Shop User";
        String email = staff.getEmail();
        String ref = "STAFF-RESET-" + staff.getId() + "-" + System.currentTimeMillis();
        if (email != null && !email.isBlank()) {
            try {
                emailNotificationClient.sendEmail(
                        email,
                        "Your SwiftInn " + roleLabel + " password has been reset",
                        buildResetText(staff.getFirstName(), email, roleLabel, tempPassword),
                        ref);
                log.info("Password-reset email sent userId={} role={}", staff.getId(), roleLabel);
                return;
            } catch (RuntimeException ex) {
                log.warn("Password-reset email failed userId={}, trying SMS: {}",
                        staff.getId(), ex.getMessage());
            }
        }
        String phone = staff.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Password-reset has no email or phone target userId={} (password still rotated)",
                    staff.getId());
            return;
        }
        String account = (email != null && !email.isBlank()) ? " (" + email + ")" : "";
        String sms = "Your SwiftInn account" + account
                + " password has been reset. New temporary password: " + tempPassword
                + ". Log in and change it immediately.";
        try {
            smsNotificationClient.sendSms(phone, sms, ref);
            log.info("Password-reset SMS sent userId={}", staff.getId());
        } catch (RuntimeException ex) {
            log.warn("Password-reset notification failed on all channels userId={} " +
                    "(password still rotated): {}", staff.getId(), ex.getMessage());
        }
    }

    private String buildResetText(String firstName, String email, String roleLabel, String tempPassword) {
        String name = (firstName != null && !firstName.isBlank()) ? firstName : "there";
        return "Hi " + name + ",\n\n"
                + "Your SwiftInn " + roleLabel + " password has been reset.\n\n"
                + "Use these credentials to sign in:\n"
                + "Username: " + email + "\n"
                + "New temporary password: " + tempPassword + "\n\n"
                + "For your security, please log in and change your password immediately. "
                + "If you didn't request this reset, contact your administrator.\n\n"
                + "— The SwiftInn Team";
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
                .loyaltyMerchantId(merchantId)
                .loyaltyShopId(shopId)
                .build();
    }

    /**
     * Deliver the new staff member their onboarding credentials. Best-effort: a
     * delivery failure must NOT block creation — the account is already
     * persisted and usable. Email is the primary channel (staff always onboard
     * with an email address, and credentials belong in an inbox rather than an
     * SMS); SMS is the fallback when the email gateway rejects the message or no
     * address is on file. Mirrors {@code UserAdminService#notifyApproval}. Never
     * logs the temporary password.
     */
    private void notifyOnboarding(User staff, String tempPassword) {
        String roleLabel = staff.hasRole(User.Role.SHOP_ADMIN) ? "Shop Administrator" : "Shop User";
        String email = staff.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailNotificationClient.sendEmail(
                        email,
                        "Welcome to SwiftInn — your account is ready",
                        buildOnboardingText(staff.getFirstName(), email, roleLabel, tempPassword),
                        "STAFF-ONBOARD-" + staff.getId());
                log.info("Onboarding email sent userId={} role={}", staff.getId(), roleLabel);
                return;
            } catch (RuntimeException emailEx) {
                log.warn("Onboarding email failed userId={}, trying SMS: {}",
                        staff.getId(), emailEx.getMessage());
            }
        } else {
            log.warn("New staff has no email; trying SMS for onboarding userId={}", staff.getId());
        }

        String phone = staff.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("New staff has no email or phone; skipping onboarding notification userId={}",
                    staff.getId());
            return;
        }
        String account = (email != null && !email.isBlank()) ? " (" + email + ")" : "";
        String sms = "Welcome to SwiftInn. Your account" + account + " is ready. Temporary password: "
                + tempPassword + ". Log in and change it immediately.";
        try {
            smsNotificationClient.sendSms(phone, sms, "STAFF-ONBOARD-" + staff.getId());
            log.info("Onboarding SMS sent userId={}", staff.getId());
        } catch (RuntimeException smsEx) {
            log.warn("Onboarding notification failed userId={} (account still created): {}",
                    staff.getId(), smsEx.getMessage());
        }
    }

    /**
     * Renders the onboarding email body. Dynamic values are HTML-escaped so a
     * name or address containing markup can't break (or inject into) the
     * message. The temporary password is a freshly-generated random value
     * (server-side, not caller-supplied) — escaped regardless as defence in depth.
     */
    private String buildOnboardingText(String firstName, String email, String roleLabel, String tempPassword) {
        String name = (firstName != null && !firstName.isBlank()) ? firstName : "there";
        return "Hi " + name + ",\n\n"
                + "A SwiftInn account has been created for you as a " + roleLabel + ".\n\n"
                + "Use these credentials to sign in:\n"
                + "Username: " + email + "\n"
                + "Temporary password: " + tempPassword + "\n\n"
                + "For your security, please log in and change your password immediately.\n\n"
                + "— The SwiftInn Team";
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
     * A MERCHANT_ADMIN may only act on shops belonging to their OWN merchant.
     * Compares the resolved shop's {@code merchantId} against the caller's
     * {@code loyaltyMerchantId}; on mismatch — or a caller with no merchant
     * scope — throws 403, which is exactly the response the controller's Swagger
     * already advertises ("Shop does not belong to your merchant") but the code
     * never enforced.
     */
    private void requireCallerOwnsShop(User caller, String shopMerchantId) {
        UUID callerMerchant = caller.getLoyaltyMerchantId();
        if (callerMerchant == null || shopMerchantId == null
                || !callerMerchant.toString().equals(shopMerchantId)) {
            throw forbidden("Shop does not belong to your merchant");
        }
    }

    private ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
