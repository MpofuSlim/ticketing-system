package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.UpdateActiveStatusDTO;
import com.innbucks.userservice.dto.UserResponseDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - User Management", description = "SUPER_ADMIN endpoints for managing user accounts.")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final UserRepository userRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final UserAdminService userAdminService;
    private final com.innbucks.userservice.service.MfaService mfaService;

    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Reset a user's 2FA (lost authenticator + lost backup codes)",
            description = """
                    Wipes the target's TOTP secret + all unused backup codes, leaving them in the
                    "must enrol" state. On their next login the policy will redirect them to
                    `/auth/mfa/enroll/start`. SUPER_ADMIN only — this is the recovery path when both
                    the authenticator app and the printed backup codes are lost.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "MFA reset",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "200 OK", "message": "MFA reset", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<ApiResult<Void>> resetMfa(@PathVariable Long id) {
        mfaService.adminReset(id);
        return ResponseEntity.ok(ApiResult.ok("MFA reset", null));
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List system users (no customers, no SUPER_ADMIN)",
            description = "Returns user accounts for the SUPER_ADMIN portal: every role **except CUSTOMER** " +
                    "by default, and **SUPER_ADMIN is always excluded** regardless of the other filters. " +
                    "Customers are the wallet-holding end-users of the super-app and are managed via the " +
                    "customer-facing surface (`/auth/customer/**`), not the admin portal — listing them " +
                    "here would drown the page in millions of rows. SUPER_ADMIN is the platform-owner " +
                    "account (seeded once via BOOTSTRAP_ADMIN_PASSWORD); it isn't a user under admin " +
                    "management and never appears in any listing.\n\n" +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for pending/inactive " +
                    "accounts. Omit to return all status values.\n\n" +
                    "Pass `?includeCustomers=true` to opt back in to the customer population (e.g. for " +
                    "support triage) — SUPER_ADMIN stays excluded even then. Defaults to `false`. " +
                    "Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Users retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Users (system, no customers) retrieved",
                                      "data": [
                                        {
                                          "id": 9,
                                          "firstName": "Rumbi",
                                          "lastName": "Moyo",
                                          "email": "rumbi@showtime.co.zw",
                                          "phoneNumber": "+263772999000",
                                          "roles": ["EVENT_ORGANIZER"],
                                          "defaultServices": ["ticketing"],
                                          "active": true,
                                          "createdAt": "2026-02-12T11:30:00",
                                          "business": true,
                                          "businessDetails": {
                                            "businessName": "Showtime Events",
                                            "businessAddress": "5 Leopold Takawira St, Bulawayo",
                                            "businessEmail": "hello@showtime.co.zw",
                                            "businessPhoneNumber": "+263292987654",
                                            "registrationNumber": "CR-2025-04412",
                                            "bpoNumber": "BPO-39007",
                                            "totalEvents": 37,
                                            "rating": 4.6
                                          }
                                        },
                                        {
                                          "id": 14,
                                          "firstName": "Farai",
                                          "lastName": "Dube",
                                          "email": "farai@acme-merch.co.zw",
                                          "phoneNumber": "+263773111222",
                                          "roles": ["SHOP_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-02-14T08:00:00",
                                          "business": false,
                                          "loyaltyMerchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                          "loyaltyShopId": "11111111-aaaa-bbbb-cccc-222222222222"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listUsers(
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "includeCustomers", required = false, defaultValue = "false") boolean includeCustomers) {

        List<User> users;
        if (includeCustomers) {
            users = (active != null) ? userRepository.findByActive(active) : userRepository.findAll();
        } else {
            users = (active != null)
                    ? userRepository.findByActiveExcludingRole(active, User.Role.CUSTOMER)
                    : userRepository.findAllExcludingRole(User.Role.CUSTOMER);
        }

        // SUPER_ADMIN never appears in any listing — it's the platform-owner
        // account, not a user under admin management. Filtered in-memory: a
        // cell has 1 SUPER_ADMIN row (BOOTSTRAP_ADMIN_PASSWORD-seeded), so
        // adding a 3rd exclusion variant of the repository query isn't worth
        // the surface — the row count makes the filter free.
        List<User> visible = users.stream()
                .filter(u -> !u.hasRole(User.Role.SUPER_ADMIN))
                .collect(Collectors.toList());

        // Batch-load tenant profiles so business accounts carry their business
        // details here too (not just on GET /admin/users/merchants), without an
        // N+1 query per user. Same pattern as listMerchants; from(u, null)
        // leaves businessDetails null for personal accounts.
        Map<Long, TenantProfile> profilesByUserId = visible.isEmpty()
                ? Map.of()
                : tenantProfileRepository
                        .findByUserIdIn(visible.stream().map(User::getId).collect(Collectors.toList()))
                        .stream()
                        .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p));

        List<UserResponseDTO> body = visible.stream()
                .map(u -> UserResponseDTO.from(u, profilesByUserId.get(u.getId())))
                .collect(Collectors.toList());

        String activeLabel = active == null ? "Users"
                : (active ? "Active users" : "Inactive users");
        String scopeLabel = includeCustomers ? "" : " (system, no customers)";
        String msg = activeLabel + scopeLabel + " retrieved";
        log.info("{} count={}", msg, body.size());
        return ResponseEntity.ok(ApiResult.ok(msg, body));
    }

    @GetMapping("/merchants")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "List merchant admins and event organizers",
            description = "Returns user accounts carrying the **MERCHANT_ADMIN** role (people who " +
                    "own/administer a merchant on the platform) **or** the **EVENT_ORGANIZER** role " +
                    "(people who run ticketed events) — the two top-level business roles. A user " +
                    "enrolled in both bundles holds both roles and appears once. SHOP_ADMIN / " +
                    "SHOP_USER staff are scoped to a single shop and are not included here; use " +
                    "`GET /admin/users` for the full system-user listing.\n\n" +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for " +
                    "pending/inactive ones. Omit to return all status values. Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Merchant admins and event organizers retrieved",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Merchant admins & event organizers retrieved",
                                      "data": [
                                        {
                                          "id": 7,
                                          "firstName": "Tendai",
                                          "lastName": "Ncube",
                                          "email": "tendai@acme-merch.co.zw",
                                          "phoneNumber": "+263772345678",
                                          "roles": ["MERCHANT_ADMIN"],
                                          "defaultServices": ["loyalty"],
                                          "active": true,
                                          "createdAt": "2026-02-10T09:15:00",
                                          "business": true,
                                          "businessDetails": {
                                            "businessName": "Acme Merchandising (Pvt) Ltd",
                                            "businessAddress": "12 Samora Machel Ave, Harare",
                                            "businessEmail": "accounts@acme-merch.co.zw",
                                            "businessPhoneNumber": "+263242123456",
                                            "registrationNumber": "CR-2026-00891",
                                            "bpoNumber": "BPO-44512",
                                            "totalEvents": 0,
                                            "rating": 0.0
                                          }
                                        },
                                        {
                                          "id": 9,
                                          "firstName": "Rumbi",
                                          "lastName": "Moyo",
                                          "email": "rumbi@showtime.co.zw",
                                          "phoneNumber": "+263772999000",
                                          "roles": ["EVENT_ORGANIZER"],
                                          "defaultServices": ["ticketing"],
                                          "active": true,
                                          "createdAt": "2026-02-12T11:30:00",
                                          "business": true,
                                          "businessDetails": {
                                            "businessName": "Showtime Events",
                                            "businessAddress": "5 Leopold Takawira St, Bulawayo",
                                            "businessEmail": "hello@showtime.co.zw",
                                            "businessPhoneNumber": "+263292987654",
                                            "registrationNumber": "CR-2025-04412",
                                            "bpoNumber": "BPO-39007",
                                            "totalEvents": 37,
                                            "rating": 4.6
                                          }
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<UserResponseDTO>>> listMerchants(
            @RequestParam(name = "active", required = false) Boolean active) {

        var businessRoles = java.util.EnumSet.of(
                User.Role.MERCHANT_ADMIN, User.Role.EVENT_ORGANIZER);

        List<User> users = (active != null)
                ? userRepository.findByActiveAndAnyRole(active, businessRoles)
                : userRepository.findByAnyRole(businessRoles);

        // Batch-load tenant profiles for business accounts so we attach
        // business details without an N+1 query per user.
        Map<Long, TenantProfile> profilesByUserId = users.isEmpty()
                ? Map.of()
                : tenantProfileRepository
                        .findByUserIdIn(users.stream().map(User::getId).collect(Collectors.toList()))
                        .stream()
                        .collect(Collectors.toMap(p -> p.getUser().getId(), p -> p));

        List<UserResponseDTO> body = users.stream()
                .map(u -> UserResponseDTO.from(u, profilesByUserId.get(u.getId())))
                .collect(Collectors.toList());

        String msg = (active == null ? "Merchant admins & event organizers"
                : (active ? "Active merchant admins & event organizers"
                          : "Inactive merchant admins & event organizers"))
                + " retrieved";
        log.info("GET /admin/users/merchants -> {} count={}", msg, body.size());
        return ResponseEntity.ok(ApiResult.ok(msg, body));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Activate or deactivate a user",
            description = "Sets the `active` flag on the specified user account. Only an active user can log in. " +
                    "**The first activation of a newly-registered system user is its approval**: the account is " +
                    "assigned a randomly-generated one-time temporary password, flagged to change it on first " +
                    "login, and the password is delivered to the user over email/SMS/WhatsApp. Subsequent " +
                    "deactivate/reactivate toggles never reset the password. If the delivery fails, re-issue " +
                    "the password via `POST /admin/users/{id}/reset-temp-password`.\n\n" +
                    "**Refuses to act on a SUPER_ADMIN target** — disabling the platform-owner account would " +
                    "lock the platform out of itself, and reactivating it requires a SUPER_ADMIN, so no caller " +
                    "is ever permitted to toggle it. The SUPER_ADMIN's `active` state is fixed at seed time " +
                    "(BOOTSTRAP_ADMIN_PASSWORD).\n\n" +
                    "Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Active status updated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "User activated",
                                      "data": {
                                        "id": 1,
                                        "firstName": "Alice",
                                        "lastName": "Moyo",
                                        "email": "alice@innbucks.co.zw",
                                        "roles": ["EVENT_ORGANIZER"],
                                        "active": true,
                                        "createdAt": "2026-01-15T10:30:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN, OR target IS a SUPER_ADMIN (always protected)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "The SUPER_ADMIN account cannot be activated or deactivated.",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> updateActiveStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActiveStatusDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        // Capture the admin's identity + request envelope so the audit row
        // (written inside setActive) ties the action back to whoever made it.
        // Authentication is non-null here — @PreAuthorize already enforced
        // hasRole('SUPER_ADMIN'), so Spring Security would have 401'd anonymous.
        String adminEmail = authentication.getName();
        AuditContext auditContext = new AuditContext(clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));

        User user = userAdminService.setActive(id, request.getActive(), adminEmail, auditContext);

        String action = request.getActive() ? "activated" : "deactivated";
        return ResponseEntity.ok(ApiResult.ok("User " + action, UserResponseDTO.from(user)));
    }

    @PostMapping("/{id}/reset-temp-password")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
            summary = "Reset a system user's temporary password",
            description = "Mints a **fresh random temporary password** for the user, flags it must-change, " +
                    "and re-delivers it over their notification channel (email → SMS → WhatsApp). This is the " +
                    "recovery path for when the original onboarding notification never reached the user — " +
                    "because temporary passwords are per-user random values (not a shared default), the " +
                    "notification is the only channel that carries the credential, so a SUPER_ADMIN needs a " +
                    "way to re-issue it.\n\n" +
                    "The old password is irretrievably hashed, so this **rotates** to a new value rather than " +
                    "re-sending the original. Refuses to act on a SUPER_ADMIN target (that credential is " +
                    "managed via the `BOOTSTRAP_ADMIN_PASSWORD` env seed). Requires **SUPER_ADMIN** role."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Temporary password reset and re-delivered",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Temporary password reset; the user has been notified",
                                      "data": {
                                        "id": 42,
                                        "firstName": "Alice",
                                        "lastName": "Moyo",
                                        "email": "alice@innbucks.co.zw",
                                        "roles": ["EVENT_ORGANIZER"],
                                        "active": true,
                                        "createdAt": "2026-01-15T10:30:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Target is a SUPER_ADMIN (credential managed via BOOTSTRAP_ADMIN_PASSWORD)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Cannot reset the temporary password of a SUPER_ADMIN; that credential is managed via BOOTSTRAP_ADMIN_PASSWORD",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> resetTemporaryPassword(
            @PathVariable Long id,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        // @PreAuthorize already enforced SUPER_ADMIN, so authentication is non-null.
        String adminEmail = authentication.getName();
        AuditContext auditContext = new AuditContext(clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));

        User user = userAdminService.resetTemporaryPassword(id, adminEmail, auditContext);
        log.info("POST /admin/users/{}/reset-temp-password by={}", id, adminEmail);
        return ResponseEntity.ok(ApiResult.ok(
                "Temporary password reset; the user has been notified", UserResponseDTO.from(user)));
    }

    /**
     * Best-effort source-IP extraction. Same shape as
     * {@code AuthController.clientIp} — behind the api-gateway every request
     * carries an {@code X-Forwarded-For} chain; the leftmost entry is the real
     * client. Falls back to {@code remoteAddr} for direct connections.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }
}
