package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.dto.CreateShopAdminDTO;
import com.innbucks.userservice.dto.CreateShopUserDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
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
import org.springframework.web.util.HtmlUtils;

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
     * Temporary password stamped on every new shop staff member and emailed to
     * them on creation (best-effort, with an SMS fallback) so they can sign in.
     * They must rotate it via POST /auth/change-password on first login. A
     * proper one-time set-password link is the eventual upgrade; until then this
     * shared default + forced rotation is the onboarding mechanism.
     */
    static final String DEFAULT_STAFF_PASSWORD = "#Pass123";

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

        User staff = buildStaff(req.getFirstName(), req.getMiddleName(), req.getLastName(),
                req.getEmail(), req.getPhoneNumber(),
                User.Role.SHOP_ADMIN, merchantId, req.getShopId());
        userRepository.save(staff);
        log.info("Created SHOP_ADMIN userId={} email={} shopId={} merchantId={} by={}",
                staff.getId(), staff.getEmail(), req.getShopId(), merchantId, caller.getEmail());
        notifyOnboarding(staff);
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
        notifyOnboarding(staff);
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
                .password(passwordEncoder.encode(DEFAULT_STAFF_PASSWORD))
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
    private void notifyOnboarding(User staff) {
        String roleLabel = staff.hasRole(User.Role.SHOP_ADMIN) ? "Shop Administrator" : "Shop User";
        String email = staff.getEmail();
        if (email != null && !email.isBlank()) {
            try {
                emailNotificationClient.sendEmail(
                        email,
                        "Welcome to InnBucks — your account is ready",
                        buildOnboardingHtml(staff.getFirstName(), email, roleLabel),
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
        String sms = "Welcome to InnBucks. Your account" + account + " is ready. Temporary password: "
                + DEFAULT_STAFF_PASSWORD + ". Log in and change it immediately.";
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
     * message. The temporary password is a fixed internal constant, not
     * caller-supplied.
     */
    private String buildOnboardingHtml(String firstName, String email, String roleLabel) {
        String name = (firstName != null && !firstName.isBlank())
                ? HtmlUtils.htmlEscape(firstName) : "there";
        return "<p>Hi " + name + ",</p>"
                + "<p>An InnBucks account has been created for you as a <strong>"
                + roleLabel + "</strong>.</p>"
                + "<p>Use these credentials to sign in:</p>"
                + "<ul>"
                + "<li><strong>Username:</strong> " + HtmlUtils.htmlEscape(email) + "</li>"
                + "<li><strong>Temporary password:</strong> " + DEFAULT_STAFF_PASSWORD + "</li>"
                + "</ul>"
                + "<p>For your security, please log in and change your password immediately.</p>"
                + "<p>— The InnBucks Team</p>";
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
