package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.StaffContactDTO;
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
import java.util.UUID;

/**
 * Service-to-service staff-contact lookup consumed by loyalty-service's
 * earn-integrity staff registry: "which phone numbers belong to staff of
 * merchant X?". Loyalty refuses a staff-typed earn whose recipient phone is
 * in that set ({@code STAFF_RECIPIENT}) — the colleague-crediting fraud shape
 * that the caller-only {@code SELF_EARN} check cannot see.
 *
 * <p>Gated by the shared {@code X-Internal-Token} header via
 * {@link InternalTokenAuthorizer} (the caller is another backend, not a
 * logged-in user). This deliberately BYPASSES the caller-merchant scoping of
 * the admin surface ({@code /admin/shop-staff/by-merchant/…}) — S2S trust is
 * the token, not a JWT identity.
 *
 * <p>An unknown merchant returns an EMPTY list, not a 404: to the consuming
 * guard "merchant with no staff" and "no such merchant" are the same fact
 * (nothing to match against), and a 404 here would double as a
 * merchant-existence oracle on the S2S surface.
 *
 * <p>Class-level {@link Hidden} keeps this out of the public Swagger UI; the
 * gateway additionally blocks {@code /users/internal/**} at the edge via the
 * {@code user-internal-deny} route, and SecurityConfig's blanket
 * {@code /users/internal/**} permitAll lets the request reach the token check
 * here. Together that's the "three files agree" contract for an internal
 * endpoint.
 */
@RestController
@RequestMapping("/users/internal/shop-staff")
@Slf4j
@Hidden
public class InternalShopStaffController {

    private final UserRepository userRepository;
    private final InternalTokenAuthorizer tokenAuthorizer;

    public InternalShopStaffController(UserRepository userRepository,
                                       InternalTokenAuthorizer tokenAuthorizer) {
        this.userRepository = userRepository;
        this.tokenAuthorizer = tokenAuthorizer;
    }

    @GetMapping("/by-merchant/{merchantId}/contacts")
    @Operation(summary = "(S2S) Phone contacts of every staff member linked to this merchant",
            description = "Returns the (possibly empty) list of {userUuid, phoneNumber} for every user "
                    + "whose loyalty_merchant_id is this merchant — SHOP_ADMIN and SHOP_USER accounts. "
                    + "Accounts without a phone are included with phoneNumber null; the consumer filters. "
                    + "Consumed by loyalty-service's STAFF_RECIPIENT earn guard.")
    public ResponseEntity<?> staffContacts(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID merchantId,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<StaffContactDTO> data = userRepository.findByLoyaltyMerchantId(merchantId).stream()
                .map(u -> StaffContactDTO.builder()
                        .userUuid(u.getUserUuid())
                        .phoneNumber(u.getPhoneNumber())
                        .build())
                .toList();
        return ResponseEntity.ok(ApiResult.ok("Merchant staff contacts resolved", data));
    }
}
