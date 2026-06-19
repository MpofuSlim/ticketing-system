package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service-to-service lookup consumed by loyalty-service: returns the set of
 * {@code loyalty_merchant_id}s that already have at least one user with the
 * given role (in practice always {@link User.Role#MERCHANT_ADMIN}).
 * loyalty-service uses the result to power the
 * {@code GET /loyalty/merchants?unassigned=true} filter — the merchants the
 * FE shows to a new merchant onboarding so they can attach themselves to a
 * yet-unclaimed one.
 *
 * <p>Gated by the shared {@code X-Internal-Token} header (constant-time
 * compare) — the caller is another backend, not a logged-in user. Mirrors
 * {@link InternalTenantLookupController} and the gateway additionally blocks
 * {@code /users/internal/**} at the edge via the {@code user-internal-deny}
 * route, so this is unreachable from the public internet.
 */
@RestController
@RequestMapping("/users/internal")
@Slf4j
@Hidden
public class InternalMerchantAssignmentController {

    private final UserRepository userRepository;
    private final InternalTokenAuthorizer tokenAuthorizer;

    public InternalMerchantAssignmentController(UserRepository userRepository,
                                                InternalTokenAuthorizer tokenAuthorizer) {
        this.userRepository = userRepository;
        this.tokenAuthorizer = tokenAuthorizer;
    }

    @GetMapping("/merchants/assigned")
    @Operation(summary = "(S2S) List loyalty_merchant_ids that already have a user with the given role",
            description = "Returns the distinct loyalty_merchant_id values stamped on users carrying "
                    + "the supplied role. loyalty-service uses this as the exclusion set for its "
                    + "unassigned-merchants picker (default role: MERCHANT_ADMIN). "
                    + "Requires X-Internal-Token.")
    public ResponseEntity<?> assignedMerchantIds(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(value = "role", defaultValue = "MERCHANT_ADMIN") String role,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User.Role parsed;
        try {
            parsed = User.Role.valueOf(role);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(HttpStatus.BAD_REQUEST, "Unknown role: " + role));
        }
        List<String> ids = userRepository.findDistinctLoyaltyMerchantIdsByRole(parsed).stream()
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toList());
        log.debug("Assigned-merchant-id lookup role={} count={}", parsed, ids.size());
        return ResponseEntity.ok(ApiResult.ok("Assigned merchant ids", ids));
    }
}
