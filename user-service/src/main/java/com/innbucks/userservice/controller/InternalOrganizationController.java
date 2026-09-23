package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.OrganizationDTOs;
import com.innbucks.userservice.service.OrganizationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Service-to-service lookups on organizations (V39), for the products that
 * now attach to an organization instead of borrowing another product's id:
 * the marketplace needs a seller's NAME for its catalogue and payout report,
 * and the PEOPLE to tell about a paid order.
 *
 * <p>Internal-only, three ways: the shared {@code X-Internal-Token} checked
 * here (constant time), {@code /users/internal/**} permitted in this service's
 * SecurityConfig so the check is what decides, and the gateway's
 * {@code user-internal-deny} route so the path is unreachable from outside.
 */
@RestController
@RequestMapping("/users/internal/organizations")
@RequiredArgsConstructor
@Slf4j
@Hidden
public class InternalOrganizationController {

    /** A catalogue page or payout run names many sellers; bounded so the IN list never is unbounded. */
    static final int MAX_IDS = 200;

    private final OrganizationService organizationService;
    private final InternalTokenAuthorizer tokenAuthorizer;

    /**
     * Display names for a batch of organizations. An unknown id is simply
     * ABSENT — one stale id in a page must not cost the rest their names.
     */
    @GetMapping("/names")
    public ResponseEntity<?> names(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(value = "ids", required = false) List<UUID> ids,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(ApiResult.ok("Organization names", List.<OrganizationDTOs.OrganizationName>of()));
        }
        if (ids.size() > MAX_IDS) {
            return ResponseEntity.badRequest().body(ApiResult.error(HttpStatus.BAD_REQUEST,
                    "At most " + MAX_IDS + " organization ids per call"));
        }
        return ResponseEntity.ok(ApiResult.ok("Organization names", organizationService.names(ids)));
    }

    /**
     * The people who may act for an organization: OWNER and ADMIN members with
     * an active account. Empty for an unknown organization, the same as for one
     * with nobody to tell, so this is never an existence oracle.
     */
    @GetMapping("/{organizationId}/admins")
    public ResponseEntity<?> admins(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable UUID organizationId,
            HttpServletRequest request) {
        if (!tokenAuthorizer.authorized(token, request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(ApiResult.ok("Organization admins", organizationService.admins(organizationId)));
    }
}
