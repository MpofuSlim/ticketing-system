package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service-to-service lookup consumed by marketplace-service: "which USER should
 * I tell that merchant X has a paid order?".
 *
 * <p><b>Why it needs a network hop of its own.</b> This service cannot answer
 * the question from its own tables. {@code users.loyalty_merchant_id} is
 * stamped by {@code ShopStaffService} on SHOP_ADMIN / SHOP_USER rows only, so a
 * MERCHANT_ADMIN's own row does NOT name their merchant — which is precisely
 * why {@code AuthService.resolveMerchantIdClaim} resolves the JWT claim by
 * asking loyalty-service by email at every login. Loyalty's
 * {@code merchants.admin_email} is the one place the link is recorded, so the
 * chain is: merchantId → (loyalty) admin email → (here) the user row.
 *
 * <p><b>Why not the shop-staff endpoint one file over.</b>
 * {@link InternalShopStaffController} already resolves users by
 * {@code loyalty_merchant_id} and would have needed no loyalty hop — but it
 * returns shop STAFF, and marketplace's fulfilment queue is gated
 * {@code hasAnyRole('MERCHANT_ADMIN','SUPER_ADMIN')}. Answering with staff
 * would notify people who cannot open the screen the notification is about,
 * while still not telling the one person who can.
 *
 * <p><b>Scoped to MERCHANT_ADMIN, deliberately.</b> An {@code admin_email} that
 * resolves to an account without that role is reported as no recipient and
 * logged, not returned: sending one merchant's order detail to an account that
 * is not a merchant admin is the worse of the two failures, and the log makes
 * the mismatch diagnosable rather than silent.
 *
 * <p>An unknown merchant, a merchant with nobody on file, an admin email with
 * no account, an inactive account and a loyalty outage all return the SAME
 * empty list. The caller's next step is identical for all of them — there is
 * nobody to notify — and a distinguishable answer would make this an existence
 * oracle for merchants and accounts on an S2S surface.
 *
 * <p>Class-level {@link Hidden} keeps it out of public Swagger; the gateway
 * blocks {@code /users/internal/**} at the edge via {@code user-internal-deny},
 * and SecurityConfig's blanket {@code /users/internal/**} permitAll lets the
 * request reach the token check here. That is the fleet's "three files agree"
 * contract, satisfied without a new entry in any of them.
 */
@RestController
@RequestMapping("/users/internal")
@Slf4j
@Hidden
public class InternalMerchantAdminController {

    private final UserRepository userRepository;
    private final LoyaltyServiceClient loyaltyServiceClient;
    private final InternalTokenAuthorizer tokenAuthorizer;

    public InternalMerchantAdminController(UserRepository userRepository,
                                           LoyaltyServiceClient loyaltyServiceClient,
                                           InternalTokenAuthorizer tokenAuthorizer) {
        this.userRepository = userRepository;
        this.loyaltyServiceClient = loyaltyServiceClient;
        this.tokenAuthorizer = tokenAuthorizer;
    }

    @GetMapping("/merchants/{merchantId}/admins")
    @Operation(summary = "(S2S) The admin users of one loyalty merchant",
            description = "Returns {userUuid, email} for every ACTIVE MERCHANT_ADMIN account bound to "
                    + "this merchant, resolved through loyalty-service's merchants.admin_email — this "
                    + "service stamps loyalty_merchant_id on shop staff only, so a merchant admin's own "
                    + "row cannot answer it. Consumed by marketplace-service to tell a seller they have "
                    + "a paid order. An unknown merchant, no admin on file, no matching account and a "
                    + "loyalty outage are all an empty list. Requires X-Internal-Token.")
    public ResponseEntity<?> merchantAdmins(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID merchantId,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<MerchantAdminDTO> admins = resolveAdmins(merchantId);
        log.debug("Merchant-admin lookup merchantId={} resolved={}", merchantId, admins.size());
        return ResponseEntity.ok(ApiResult.ok("Merchant admins resolved", admins));
    }

    /** Never throws: the client is best-effort and every miss is an empty list. */
    private List<MerchantAdminDTO> resolveAdmins(UUID merchantId) {
        Optional<String> adminEmail = loyaltyServiceClient.adminEmailForMerchant(merchantId);
        if (adminEmail.isEmpty()) {
            return List.of();
        }
        String email = adminEmail.get();
        Optional<User> account = userRepository.findByEmail(email);
        if (account.isEmpty()) {
            // A merchant onboarded in loyalty whose admin has not signed up here
            // yet. Ordinary, and the caller's answer is the same as any other
            // miss — but worth a line, because it is also what a typo in
            // merchants.admin_email looks like.
            log.info("Merchant admin email has no user account merchantId={}", merchantId);
            return List.of();
        }
        User user = account.get();
        if (!user.isActive() || !user.getRoles().contains(User.Role.MERCHANT_ADMIN.name())) {
            // Named as the merchant's admin in loyalty, but this account cannot
            // act on the merchant here. Deliberately not returned: routing one
            // merchant's order detail to a non-admin account is the worse
            // failure, and this log is what makes the mismatch findable.
            log.warn("Merchant admin account is inactive or not a MERCHANT_ADMIN merchantId={} active={}",
                    merchantId, user.isActive());
            return List.of();
        }
        return List.of(new MerchantAdminDTO(user.getUserUuid(), user.getEmail()));
    }

    /**
     * A list of one, today. The shape is plural because the question is: a
     * merchant's admin is one email column now, but nothing downstream should
     * be written against that being permanent — a second admin, or a merchant
     * with a team, changes this endpoint and no caller.
     */
    public record MerchantAdminDTO(UUID userUuid, String email) {
    }
}
