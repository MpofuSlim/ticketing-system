package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.SuperAdminDTOs;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.SuperAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * More than one SUPER_ADMIN (V52). Every SUPER_ADMIN may list them; only the
 * SEEDED platform owner may promote or demote ({@code PlatformOwnerGuard}, read
 * from the caller's live row). Routed by the gateway's {@code user-admin-route}
 * ({@code Path=/admin/**}), so no gateway change.
 *
 * <p>Gated on the role NAME ({@code hasRole('SUPER_ADMIN')}), not a permission:
 * the question is "is this a platform owner-class account", which a permission
 * could be composed onto a lesser role to answer wrongly, and the promote/demote
 * owner check sits under it in the service either way.
 */
@RestController
@RequestMapping("/admin/super-admins")
@RequiredArgsConstructor
@Tag(name = "Admin — SUPER_ADMIN accounts",
        description = "List the SUPER_ADMIN accounts; the seeded platform owner promotes and demotes deputies.")
@SecurityRequirement(name = "bearerAuth")
public class AdminSuperAdminController {

    private final SuperAdminService superAdminService;

    // ---------------------------------------------------------------------
    // Shared example bodies — the owner (id 1) and one deputy (id 4812), the
    // same records on every endpoint.
    // ---------------------------------------------------------------------

    private static final String OWNER_VIEW = """
            {
                    "userId": 1,
                    "userUuid": "3f1d2c4b-5a6e-4f70-8a9b-0c1d2e3f4a5b",
                    "firstName": "Super",
                    "lastName": "Admin",
                    "email": "admin@innbucks.co.zw",
                    "platformOwner": true,
                    "superAdmin": true,
                    "active": true,
                    "mfaEnabled": true,
                    "roles": ["SUPER_ADMIN"]
                  }""";

    private static final String DEPUTY_VIEW = """
            {
                    "userId": 4812,
                    "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                    "firstName": "Tariro",
                    "lastName": "Moyo",
                    "email": "tariro.moyo@innbucks.co.zw",
                    "platformOwner": false,
                    "superAdmin": true,
                    "active": true,
                    "mfaEnabled": true,
                    "roles": ["CALL_CENTER_SUPERVISOR", "SUPER_ADMIN"]
                  }""";

    static final String PROMOTED_MESSAGE =
            "Promoted to SUPER_ADMIN. The account has been signed out everywhere and must sign in again to use it.";
    static final String DEMOTED_MESSAGE =
            "SUPER_ADMIN removed. The account has been signed out everywhere and keeps its other roles.";

    private static final String UNAUTHORIZED = """
            { "code": "401 UNAUTHORIZED", "message": "Invalid token", "data": null }
            """;
    private static final String NOT_A_SUPER_ADMIN_CALLER = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
            """;
    private static final String PLATFORM_OWNER_ONLY = """
            {
              "code": "403 FORBIDDEN",
              "message": "Only the platform owner can promote or demote a SUPER_ADMIN.",
              "data": { "errorCode": "platform_owner_only" }
            }
            """;
    private static final String NOT_FOUND = """
            { "code": "404 NOT_FOUND", "message": "User not found: 9999", "data": null }
            """;
    private static final String BAD_ID = """
            { "code": "400 BAD_REQUEST", "message": "We couldn't process your request. Please try again.", "data": null }
            """;
    private static final String AUDIT_UNAVAILABLE = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "We couldn't record this change, so it wasn't made. Try again.",
              "data": { "errorCode": "audit_unavailable" }
            }
            """;

    // ---------------------------------------------------------------------
    // GET /admin/super-admins
    // ---------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "List the SUPER_ADMIN accounts",
            description = """
                    Every account holding SUPER_ADMIN: the seeded platform owner (`platformOwner: true`, always
                    first) and any deputies the owner promoted, then by first name, last name and id.
                    Readable by any SUPER_ADMIN.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SUPER_ADMIN accounts retrieved",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "The owner and one deputy",
                            value = "{\n  \"code\": \"200 OK\",\n  \"message\": \"SUPER_ADMIN accounts retrieved\",\n"
                                    + "  \"data\": [\n    " + OWNER_VIEW + ",\n    " + DEPUTY_VIEW + "\n  ]\n}"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invalid token", value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "The caller is not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not a SUPER_ADMIN", value = NOT_A_SUPER_ADMIN_CALLER)))
    })
    public ResponseEntity<ApiResult<List<SuperAdminDTOs.SuperAdminView>>> list() {
        return ResponseEntity.ok(ApiResult.ok("SUPER_ADMIN accounts retrieved", superAdminService.list()));
    }

    // ---------------------------------------------------------------------
    // POST /admin/super-admins/{userId}
    // ---------------------------------------------------------------------

    @PostMapping("/{userId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Promote an account to SUPER_ADMIN (platform owner only)",
            description = """
                    Adds SUPER_ADMIN to the account's roles (its other roles stay). **Only the seeded platform
                    owner may call this**; a deputy SUPER_ADMIN gets `403 platform_owner_only`.

                    The account must be an InnBucks staff account that is active and approved, on a staff email
                    domain, with its email confirmed by accepting its staff invite, holding only staff roles and
                    belonging to no business (`409 super_admin_target_ineligible`, `data.reason` one of
                    `account_inactive`, `not_approved`, `off_domain`, `unverified`, `no_profile`,
                    `holds_non_staff_roles`, `organization_member`), and it must already have two-step
                    verification set up (`409 mfa_not_enrolled`). A merchant, organizer or customer account can
                    never be promoted: create the person a staff account with `POST /admin/staff` first.

                    The account is signed out everywhere at once (every session and remembered device ended)
                    and gets SUPER_ADMIN's full authority at its next sign-in. Nobody can deactivate, reset or
                    change the roles of a SUPER_ADMIN through the other admin endpoints; to remove a deputy,
                    demote it first.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Promoted; the account was signed out",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Promoted",
                            value = "{\n  \"code\": \"200 OK\",\n  \"message\": \"" + PROMOTED_MESSAGE + "\",\n"
                                    + "  \"data\": " + DEPUTY_VIEW + "\n}"))),
            @ApiResponse(responseCode = "400", description = "The userId is not a number",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Bad id", value = BAD_ID))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invalid token", value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "The caller is not a SUPER_ADMIN, or is a deputy",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "A deputy SUPER_ADMIN", value = PLATFORM_OWNER_ONLY),
                            @ExampleObject(name = "Not a SUPER_ADMIN", value = NOT_A_SUPER_ADMIN_CALLER)
                    })),
            @ApiResponse(responseCode = "404", description = "No account with this id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unknown account", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The account can't be promoted",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Already a SUPER_ADMIN", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account is already a SUPER_ADMIN.",
                                      "data": { "errorCode": "already_super_admin" }
                                    }
                                    """),
                            @ExampleObject(name = "Not a staff account (a merchant)", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Only an InnBucks staff account can become a SUPER_ADMIN: this email is not a staff address.",
                                      "data": { "errorCode": "super_admin_target_ineligible", "reason": "off_domain" }
                                    }
                                    """),
                            @ExampleObject(name = "Invite not accepted", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Only an InnBucks staff account that has accepted its invite can become a SUPER_ADMIN.",
                                      "data": { "errorCode": "super_admin_target_ineligible", "reason": "no_profile" }
                                    }
                                    """),
                            @ExampleObject(name = "Deactivated", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Only an active account can become a SUPER_ADMIN.",
                                      "data": { "errorCode": "super_admin_target_ineligible", "reason": "account_inactive" }
                                    }
                                    """),
                            @ExampleObject(name = "Holds business roles", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account also holds business roles. Remove them with PUT /admin/users/{id}/roles first.",
                                      "data": { "errorCode": "super_admin_target_ineligible", "reason": "holds_non_staff_roles" }
                                    }
                                    """),
                            @ExampleObject(name = "No two-step verification", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account hasn't set up two-step verification. They must sign in and set it up before they can become a SUPER_ADMIN.",
                                      "data": { "errorCode": "mfa_not_enrolled" }
                                    }
                                    """)
                    })),
            @ApiResponse(responseCode = "503", description = "Staff domains not configured, or the audit row could not be written",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "No staff domain configured", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Staff accounts aren't set up on this server yet.",
                                      "data": { "errorCode": "staff_domains_unconfigured" }
                                    }
                                    """),
                            @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)
                    }))
    })
    public ResponseEntity<ApiResult<SuperAdminDTOs.SuperAdminView>> promote(
            @Parameter(description = "users.id of the account to promote", example = "4812")
            @PathVariable Long userId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        var promoted = superAdminService.promote(userId, authentication.getName(), context(httpRequest));
        return ResponseEntity.ok(ApiResult.ok(PROMOTED_MESSAGE, SuperAdminDTOs.SuperAdminView.of(promoted)));
    }

    // ---------------------------------------------------------------------
    // DELETE /admin/super-admins/{userId}
    // ---------------------------------------------------------------------

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Demote a deputy SUPER_ADMIN (platform owner only)",
            description = """
                    Removes SUPER_ADMIN from a deputy. **Only the seeded platform owner may call this**; a deputy
                    gets `403 platform_owner_only`. The platform owner itself can never be demoted, by anyone
                    (`403 target_not_manageable`, `reason: platform_owner`).

                    The account keeps its other roles and stays active, and is signed out everywhere at once
                    (every session and remembered device ended). An account left with no roles at all can still
                    sign in but is authorized for nothing. To switch the person off as well, demote first, then
                    deactivate through `POST /admin/staff/{id}/deactivate`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Demoted; the account was signed out",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Demoted",
                            value = """
                                    {
                                      "code": "200 OK",
                                      "message": "SUPER_ADMIN removed. The account has been signed out everywhere and keeps its other roles.",
                                      "data": {
                                        "userId": 4812,
                                        "userUuid": "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11",
                                        "firstName": "Tariro",
                                        "lastName": "Moyo",
                                        "email": "tariro.moyo@innbucks.co.zw",
                                        "platformOwner": false,
                                        "superAdmin": false,
                                        "active": true,
                                        "mfaEnabled": true,
                                        "roles": ["CALL_CENTER_SUPERVISOR"]
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "The userId is not a number",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Bad id", value = BAD_ID))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invalid token", value = UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "The caller is a deputy or not a SUPER_ADMIN, or the target is the platform owner",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "A deputy SUPER_ADMIN", value = PLATFORM_OWNER_ONLY),
                            @ExampleObject(name = "The platform owner", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "You can't change this account.",
                                      "data": { "errorCode": "target_not_manageable", "reason": "platform_owner" }
                                    }
                                    """),
                            @ExampleObject(name = "Not a SUPER_ADMIN", value = NOT_A_SUPER_ADMIN_CALLER)
                    })),
            @ApiResponse(responseCode = "404", description = "No account with this id",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unknown account", value = NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The account is not a SUPER_ADMIN",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Not a SUPER_ADMIN", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This account is not a SUPER_ADMIN.",
                                      "data": { "errorCode": "not_super_admin" }
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The audit row could not be written",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE)))
    })
    public ResponseEntity<ApiResult<SuperAdminDTOs.SuperAdminView>> demote(
            @Parameter(description = "users.id of the deputy to demote", example = "4812")
            @PathVariable Long userId,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        var demoted = superAdminService.demote(userId, authentication.getName(), context(httpRequest));
        return ResponseEntity.ok(ApiResult.ok(DEMOTED_MESSAGE, SuperAdminDTOs.SuperAdminView.of(demoted)));
    }

    private static AuditContext context(HttpServletRequest request) {
        return new AuditContext(clientIp(request), request.getHeader("User-Agent"));
    }

    /** Leftmost {@code X-Forwarded-For} entry behind the gateway, else the remote address (as AdminStaffController). */
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
