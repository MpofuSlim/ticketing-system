package com.innbucks.userservice.controller;

import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.UpdateActiveStatusDTO;
import com.innbucks.userservice.dto.UpdateRolesDTO;
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
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_MFA_RESET + "')")
    @Operation(summary = "Reset a user's 2FA (lost authenticator + lost backup codes)",
            description = """
                    Wipes the target's TOTP secret + all unused backup codes and every "remember this
                    device" trust, leaving them in the "must enrol" state. On their next login the policy
                    will redirect them to `/auth/mfa/enroll/start`. This is the recovery path when both the
                    authenticator app and the printed backup codes are lost.

                    **Takes effect immediately:** the account's session epoch (`tokenVersion`) is bumped,
                    so every access token it holds — in user-service at once, and in every other service as
                    soon as the shared Redis entry is published after commit — and any half-finished 2FA
                    sign-in are ended. Refresh tokens survive, but a refresh by an account whose role
                    requires 2FA is refused (`403 mfa_enrollment_required`) until they re-enrol.

                    **Audit:** recorded as `MFA_ADMIN_RESET` with the calling administrator as the actor and
                    the user as the target. The optional `note` (at most 500 characters) is stored on that
                    row only — it is never sent to the user. The body may be omitted entirely.

                    **Refuses a SUPER_ADMIN target** with `403 target_not_manageable` (`reason:
                    super_admin`): the platform-owner account is managed only through
                    `BOOTSTRAP_ADMIN_PASSWORD`.

                    **Refuses a target holding any permission the caller does not, or a platform staff
                    built-in (`PRODUCT_*`, `CALL_CENTER_*`, `FRAUD_DESK`) the caller does not hold
                    themselves** — `403 target_not_manageable` (`reason: exceeds_your_authority`), read
                    from the caller's current roles (SUPER_ADMIN passes both). Resetting someone's 2FA
                    opens their account to whoever holds their password, so a narrower administrator
                    cannot do it to a broader one — including a `PRODUCT_MANAGER`, whose authority is
                    mostly its name in event-service and booking-service.

                    Requires the `users:mfa:reset` permission.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "MFA reset; the user's sessions have ended",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "200 OK", "message": "MFA reset", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "The note is longer than 500 characters",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Note too long", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "note": "note must be 500 characters or fewer" }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing, expired or ended bearer token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "The caller lacks `users:mfa:reset`, the target is the SUPER_ADMIN account, or "
                            + "the target holds a permission the caller does not",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Target is SUPER_ADMIN", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "You can't change this account.",
                                              "data": { "errorCode": "target_not_manageable", "reason": "super_admin" }
                                            }
                                            """),
                                    @ExampleObject(name = "Target holds more than the caller", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "You can't change this account.",
                                              "data": { "errorCode": "target_not_manageable", "reason": "exceeds_your_authority" }
                                            }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No user with that id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "User not found: 999", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<Void>> resetMfa(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) com.innbucks.userservice.dto.AdminMfaResetRequestDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        // Authentication is non-null here — @PreAuthorize already enforced the
        // permission. Its name (the admin's email) is the audit ACTOR; the user
        // being reset is the TARGET.
        AuditContext auditContext = new AuditContext(clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
        mfaService.adminReset(id, authentication.getName(),
                request == null ? null : request.getNote(), auditContext);
        return ResponseEntity.ok(ApiResult.ok("MFA reset", null));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_READ + "')")
    @Operation(
            summary = "List system users (no customer-only accounts)",
            description = "Returns user accounts for the admin portal: every account **except those whose " +
                    "only role is CUSTOMER**. An account holding CUSTOMER alongside any other role (a " +
                    "merchant admin who also shops in the super app) IS listed, and its `roles` omit " +
                    "`CUSTOMER` — the console neither shows nor manages the super-app side of an account. " +
                    "Customer-only accounts are the wallet-holding end-users of the super app and would " +
                    "drown the page in millions of rows.\n\n" +
                    "**SUPER_ADMIN holders are listed.** The write endpoints still refuse to act on them " +
                    "(deactivate, roles and MFA reset answer 403, temp-password reset 400), so hide those " +
                    "actions on a row whose `roles` contain `SUPER_ADMIN`.\n\n" +
                    "Pass `?active=true` for approved/active accounts, `?active=false` for pending/inactive " +
                    "accounts. Omit to return all status values.\n\n" +
                    "Pass `?includeCustomers=true` to opt back in to the customer-only population (e.g. " +
                    "for support triage). Defaults to `false`. " +
                    "Requires the `users:read` permission."
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

        // Customer-ONLY accounts stay off the page; an account holding CUSTOMER
        // alongside any other role is listed (a merchant admin who also shops
        // used to vanish). SUPER_ADMIN holders are listed too: once there is
        // more than the one seeded owner, hiding them hides exactly the accounts
        // whose existence an operator most needs to see. The write endpoints
        // still refuse to act on them (the SUPER_ADMIN guards in
        // UserAdminService and MfaService), so listing grants nothing.
        List<User> visible;
        if (includeCustomers) {
            visible = (active != null) ? userRepository.findByActive(active) : userRepository.findAll();
        } else {
            visible = (active != null)
                    ? userRepository.findByActiveExceptOnlyRole(active, User.Role.CUSTOMER.name())
                    : userRepository.findAllExceptOnlyRole(User.Role.CUSTOMER.name());
        }

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
    // Product staff need this to filter the platform-wide event list by
    // organizer — we gave them every organizer's events and no way to narrow
    // them. Read-only list of staff account names; no write path is widened.
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_MERCHANTS_READ + "')")
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

        var businessRoles = User.roleNames(
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
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_ACTIVATION_WRITE + "')")
    @Operation(
            summary = "Activate or deactivate a user",
            description = "Sets the `active` flag on the specified user account. Only an active user can log in. " +
                    "**The first activation of a newly-registered system user is its approval**: the account is " +
                    "assigned a randomly-generated one-time temporary password, flagged to change it on first " +
                    "login, and the password is delivered to the user over email/SMS/WhatsApp. Subsequent " +
                    "deactivate/reactivate toggles never reset the password. If the delivery fails, re-issue " +
                    "the password via `POST /admin/users/{id}/reset-temp-password`.\n\n" +
                    "**Deactivating (`active: false`) signs the user out everywhere, at once.** In the same " +
                    "transaction the account's session epoch (`tokenVersion`) is bumped, every refresh token " +
                    "is revoked, \"remember this device\" trust is cleared and any live password-reset code " +
                    "is deleted (and no new one can be requested while the account is off). Their next request " +
                    "to user-service is refused with `401 ACCOUNT_DEACTIVATED`; " +
                    "other services refuse the old access token as soon as the new version is published to " +
                    "the shared Redis after commit (if that publish fails they fall back to the access-token " +
                    "expiry). `/auth/refresh`, `/auth/organization-context` and any half-finished 2FA sign-in " +
                    "answer `401 account_inactive`. Re-activating does not restore any of it: it bumps the " +
                    "session epoch again and revokes any refresh token and device trust still on file (an " +
                    "account deactivated before deactivation ended sessions may still hold some), so the user " +
                    "signs in again from a clean slate.\n\n" +
                    "**Refuses to act on a SUPER_ADMIN target** — disabling the platform-owner account would " +
                    "lock the platform out of itself, and reactivating it requires a SUPER_ADMIN, so no caller " +
                    "is ever permitted to toggle it. The SUPER_ADMIN's `active` state is fixed at seed time " +
                    "(BOOTSTRAP_ADMIN_PASSWORD).\n\n" +
                    "**Deactivating a STAFF-role holder needs the caller to hold every permission the " +
                    "account holds, and every platform staff built-in it holds** (read from the caller's " +
                    "current roles; SUPER_ADMIN passes both) — otherwise `403 target_not_manageable` " +
                    "(`reason: exceeds_your_authority`), so a narrower administrator cannot switch off a " +
                    "broader one. A staff role is a platform staff built-in (PRODUCT_*, " +
                    "CALL_CENTER_*, FRAUD_DESK) or any role holding a PLATFORM permission; business " +
                    "accounts are not gated this way.\n\n" +
                    "**Staff accounts are switched ON only through `POST /admin/staff/{id}/reactivate`** " +
                    "(409 `use_staff_endpoints` here): that endpoint re-checks the email domain and resets the " +
                    "credentials.\n\n" +
                    "Requires the `users:activation:write` permission."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Active status updated",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Activated", value = """
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
                                                "createdAt": "2026-01-15T12:30:00+02:00"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Deactivated (signed out everywhere)", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "User deactivated",
                                              "data": {
                                                "id": 1,
                                                "firstName": "Alice",
                                                "lastName": "Moyo",
                                                "email": "alice@innbucks.co.zw",
                                                "roles": ["EVENT_ORGANIZER"],
                                                "active": false,
                                                "createdAt": "2026-01-15T12:30:00+02:00"
                                              }
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`active` missing from the body",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Validation failed", value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "Validation failed",
                                      "data": { "active": "active field is required" }
                                    }
                                    """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`active: true` on a staff account",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Use the staff endpoints", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Reactivate staff with POST /admin/staff/{id}/reactivate.",
                                      "data": { "errorCode": "use_staff_endpoints" }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No user with that id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "User not found: 999", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller lacks `users:activation:write`, the target IS a SUPER_ADMIN (always "
                            + "protected), or the target is a staff-role holder with a permission the caller lacks",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Target is SUPER_ADMIN", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "The SUPER_ADMIN account cannot be activated or deactivated.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Staff target holds more than the caller", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "You can't change this account.",
                                              "data": { "errorCode": "target_not_manageable", "reason": "exceeds_your_authority" }
                                            }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """)
                            }))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> updateActiveStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActiveStatusDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        // Capture the admin's identity + request envelope so the audit row
        // (written inside setActive) ties the action back to whoever made it.
        // Authentication is non-null here — @PreAuthorize already required
        // users:activation:write, so Spring Security would have 401'd anonymous.
        // The name is also what bounds a staff deactivation: setActive reads this
        // account's LIVE roles and refuses a target it does not cover.
        String adminEmail = authentication.getName();
        AuditContext auditContext = new AuditContext(clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));

        User user = userAdminService.setActive(id, request.getActive(), adminEmail, auditContext);

        String action = request.getActive() ? "activated" : "deactivated";
        return ResponseEntity.ok(ApiResult.ok("User " + action, UserResponseDTO.from(user)));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_ROLES_WRITE + "')")
    @Operation(
            summary = "Replace a user's roles",
            description = """
                    Sets the complete role set on the specified account. This is a **replace, not a
                    merge** — the submitted array becomes the account's entire role set, so include
                    every role the user should keep. Re-submitting the roles the user already has is
                    an idempotent no-op (no audit row, no session change).

                    ### The built-in roles

                    | Role | What it is | How it is normally assigned |
                    |---|---|---|
                    | `SUPER_ADMIN` | Platform owner. Full access to every admin endpoint. | Seeded once from `BOOTSTRAP_ADMIN_PASSWORD`. **Never grantable or revocable here.** |
                    | `PRODUCT_OFFICER` | Internal platform staff. Not scoped to a tenant, merchant, shop or organizer, and grants no service bundle. Treated as a system user for MFA and admin listing. | This endpoint, by a caller who holds it (or SUPER_ADMIN). |
                    | `PRODUCT_MANAGER` | Internal platform staff, same shape as `PRODUCT_OFFICER`. | This endpoint, by a caller who holds it (or SUPER_ADMIN). |
                    | `CALL_CENTER_AGENT` | Customer support: looks customers up and performs routine support actions (`device-security:read`, `device-security:manage`, `marketplace-support:read`, `marketplace-support:manage`, `loyalty-support:read`, `loyalty-support:manage`, `customer-messages:send`). | This endpoint, by a caller who holds it (or SUPER_ADMIN). |
                    | `CALL_CENTER_SUPERVISOR` | Customer-support supervisor: the agent's grants plus `marketplace-support:supervise` and `loyalty-support:supervise`. | This endpoint, by a caller who holds it (or SUPER_ADMIN). |
                    | `FRAUD_DESK` | Add-on held with an agent or supervisor role: bans, fraud holds and lifting them (`device-security:read`, `device-security:fraud`). | This endpoint, by a caller who holds it (or SUPER_ADMIN). |
                    | `EVENT_ORGANIZER` | Runs ticketed events — owns events, invoices, settlements and team members. | Self-registration as a business account, then `PUT /admin/users/{id}/active` to approve. |
                    | `TEAM_MEMBER` | Gate staff / scanner operator working for one EVENT_ORGANIZER. Their JWT carries the parent organizer's uuid so booking-service can authorize ticket scans. | `POST /event-organizer/team-members` |
                    | `MERCHANT_ADMIN` | Runs a loyalty merchant — manages that merchant's shops, staff and rules. | `POST /loyalty/merchants` plus the merchant-admin account. |
                    | `SHOP_ADMIN` | Manages staff at one loyalty shop. | `POST /admin/shop-staff/admins` |
                    | `SHOP_USER` | Operates the POS at one loyalty shop — records purchases and redemptions. | `POST /admin/shop-staff/users` |
                    | `CUSTOMER` | End user — earns and redeems loyalty points, buys tickets. | Self-registration. |

                    ### Session impact

                    Roles are baked into the JWT at login and every service authorizes from the token's
                    claims, so a role change **bumps the account's `token_version`**. That immediately
                    invalidates the user's existing access token fleet-wide — otherwise a demoted user
                    would keep their old privileges until the token expired. The user picks up the new
                    roles on their next login, or silently via `POST /auth/refresh`.

                    ### No escalation

                    The caller's authority is read from their CURRENT roles, never their token.

                    * **Every role ADDED** must grant only permissions the caller holds, and a
                      platform staff built-in (`PRODUCT_*`, `CALL_CENTER_*`, `FRAUD_DESK`) also needs
                      the caller to hold that same role (or be SUPER_ADMIN) — **400
                      `role_not_assignable`**, `data.roles` naming each refused role with its reason
                      (`exceeds_your_authority`, `named_role_not_held`, `reserved_to_super_admin`). A
                      stored code the catalog no longer defines counts as one the caller does not
                      hold. Roles the account already
                      holds are not re-checked.
                    * **Every role ADDED** that stores a code reserved to SUPER_ADMIN (`roles:write`,
                      `users:roles:write` — a legacy grant from before they were reserved) can be given
                      by SUPER_ADMIN only — **400 `role_not_assignable`** (`reserved_to_super_admin`).
                    * **Removing any role** needs the caller to hold every permission the account
                      holds now, and every platform staff built-in it holds — **403
                      `target_not_manageable`** (`reason: exceeds_your_authority`), so a narrower
                      administrator cannot strip a broader one (a `PRODUCT_MANAGER` included, whose
                      authority is mostly its name in other services).

                    ### Refusals

                    * The target already being a `SUPER_ADMIN`, or `SUPER_ADMIN` appearing in the
                      submitted set — **403** either way.
                    * `SHOP_ADMIN` / `SHOP_USER` on an account with no loyalty merchant + shop, or
                      `TEAM_MEMBER` on an account with no parent organizer — **400**. Those roles
                      authorize off scope baked in at creation time; granting one without it produces
                      an account that logs in and then fails inside every handler.
                    * The `USER_ROLES_CHANGED` audit row cannot be written — **503
                      `audit_unavailable`**, and nothing changes.

                    ### Staff accounts

                    * A **staff role** (a platform staff built-in, or any role holding a platform
                      permission) may only be ADDED to a staff-eligible account: an address on a staff
                      domain whose email was proven by redeeming an invite — **400
                      `email_domain_not_allowed`** off the staff domains, **400 `staff_email_unverified`**
                      otherwise (create the account with `POST /admin/staff`, or adopt a legacy one with
                      `POST /admin/staff/{id}/resend-invite`, first). 503 `staff_domains_unconfigured` on
                      a cell with no staff domain.
                    * An account with a staff profile holds staff roles only — a business role in the set
                      is **400 `role_not_assignable`** (`not_a_staff_role`).
                    * Removing a role is never refused on these grounds.

                    Requires the `users:roles:write` permission, which only SUPER_ADMIN holds: it is
                    reserved to the `*` wildcard and cannot be granted to any role through the API.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "Roles replaced; the user's existing sessions are invalidated",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Roles updated",
                                      "data": {
                                        "id": 42,
                                        "firstName": "Alice",
                                        "lastName": "Moyo",
                                        "email": "alice@innbucks.co.zw",
                                        "roles": ["CALL_CENTER_AGENT", "FRAUD_DESK"],
                                        "active": true,
                                        "createdAt": "2026-01-15T10:30:00+02:00"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Empty role set, an unrecognised role name, a role whose required scope is missing, "
                            + "or a role the caller may not give",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Role the caller may not give", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These roles can't be given to this account: FRAUD_DESK (grants more than you hold), PRODUCT_OFFICER (only someone who holds this role can give it).",
                                              "data": {
                                                "errorCode": "role_not_assignable",
                                                "roles": {
                                                  "FRAUD_DESK": "exceeds_your_authority",
                                                  "PRODUCT_OFFICER": "named_role_not_held"
                                                }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Staff role for an off-domain account", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Staff accounts must use an InnBucks email address ending in @innbucks.co.zw or @innbucks.co.ke.",
                                              "data": {
                                                "errorCode": "email_domain_not_allowed",
                                                "field": "email",
                                                "allowedDomains": ["innbucks.co.zw", "innbucks.co.ke"]
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Staff role for an account never invited", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "This account's email has never been confirmed. Send them a staff invite first.",
                                              "data": { "errorCode": "staff_email_unverified" }
                                            }
                                            """),
                                    @ExampleObject(name = "Business role for a staff account", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These roles can't be given to this account: MERCHANT_ADMIN (not a staff role).",
                                              "data": {
                                                "errorCode": "role_not_assignable",
                                                "roles": { "MERCHANT_ADMIN": "not_a_staff_role" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Legacy role storing a code reserved to SUPER_ADMIN", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These roles can't be given to this account: SUPPORT_ADMIN (reserved to SUPER_ADMIN).",
                                              "data": {
                                                "errorCode": "role_not_assignable",
                                                "roles": {
                                                  "SUPPORT_ADMIN": "reserved_to_super_admin"
                                                }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Unknown role", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Unknown role(s): CALL_CENTRE_AGENT. List the available roles with GET /admin/roles.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Empty role set", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "roles must contain at least one role",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Shop role without a shop", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "SHOP_ADMIN and SHOP_USER require the account to be scoped to a loyalty merchant and shop; create shop staff via POST /admin/shop-staff/admins or POST /admin/shop-staff/users instead.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Team member without an organizer", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "TEAM_MEMBER requires the account to be stamped with its parent EVENT_ORGANIZER; create team members via POST /event-organizer/team-members instead.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "Caller lacks `users:roles:write`, target IS a SUPER_ADMIN, SUPER_ADMIN was "
                            + "requested, or a role is being removed from an account holding more than the caller",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Removing a role from an account holding more than the caller", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "You can't change this account.",
                                              "data": { "errorCode": "target_not_manageable", "reason": "exceeds_your_authority" }
                                            }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """),
                                    @ExampleObject(name = "Target is the platform owner", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "The SUPER_ADMIN account's roles cannot be changed.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Escalation attempt", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "SUPER_ADMIN cannot be granted through this endpoint; that account is seeded once via BOOTSTRAP_ADMIN_PASSWORD.",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "User not found: 999", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "The USER_ROLES_CHANGED audit row could not be written (nothing changed), or "
                            + "a staff role was added on a cell with no staff domain",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Audit unavailable", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "We couldn't record this change, so it wasn't made. Try again.",
                                      "data": { "errorCode": "audit_unavailable" }
                                    }
                                    """),
                                    @ExampleObject(name = "No staff domain configured", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Staff accounts aren't set up on this server yet.",
                                      "data": { "errorCode": "staff_domains_unconfigured" }
                                    }
                                    """)
                            }))
    })
    public ResponseEntity<ApiResult<UserResponseDTO>> updateRoles(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRolesDTO request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        // @PreAuthorize already enforced users:roles:write, so authentication is
        // non-null. Its name is the caller whose LIVE roles bound what they may
        // add or remove (RoleGrantGuard).
        String adminEmail = authentication.getName();
        AuditContext auditContext = new AuditContext(clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));

        User user = userAdminService.setRoles(id, request.getRoles(), adminEmail, auditContext);
        log.info("PUT /admin/users/{}/roles by={} roles={}", id, adminEmail, request.getRoles());
        return ResponseEntity.ok(ApiResult.ok("Roles updated", UserResponseDTO.from(user)));
    }

    @PostMapping("/{id}/reset-temp-password")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.USERS_PASSWORD_RESET + "')")
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
                    "managed via the `BOOTSTRAP_ADMIN_PASSWORD` env seed). **Staff set passwords through an " +
                    "invite**: a staff account (or a legacy one that could be adopted) is refused with 409 " +
                    "`use_staff_invite` — use `POST /admin/staff/{id}/resend-invite`. Requires the " +
                    "`users:password:reset` permission."
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
                                        "createdAt": "2026-01-15T10:30:00+02:00"
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
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not found", value = """
                                    { "code": "404 NOT_FOUND", "message": "User not found: 999", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller lacks users:password:reset",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Missing permission", value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "A staff account: staff set passwords through an invite",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Use the staff invite", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Staff set passwords through an invite.",
                                      "data": { "errorCode": "use_staff_invite" }
                                    }
                                    """)))
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
