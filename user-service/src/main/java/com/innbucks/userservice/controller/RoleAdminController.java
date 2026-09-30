package com.innbucks.userservice.controller;

import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.dto.RoleDTOs;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.RoleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Role administration (V35) — where an operator composes a new role out of the
 * permissions the platform already enforces, and assigns it with
 * {@code PUT /admin/users/{id}/roles} like any built-in.
 *
 * <p>There is deliberately no endpoint that creates a PERMISSION. Permissions
 * are the vocabulary the {@code @PreAuthorize} checks are written against, so
 * one invented at runtime would be a string nothing consults — see
 * {@link PermissionCatalog} for the full reasoning.
 */
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin - Roles & Permissions",
     description = "Create and manage roles as named bundles of permissions. Roles are data; the "
             + "permissions they compose are defined in code and listed by GET /admin/roles/permissions. "
             + "Nobody can hand out more than they hold: codes added to a role must be held by the "
             + "caller, and the codes that hand out authority (roles:write, users:roles:write) are "
             + "reserved to SUPER_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
public class RoleAdminController {

    private final RoleAdminService roleAdminService;

    /** The 503 a role change answers when its required audit row cannot be written. */
    static final String AUDIT_UNAVAILABLE_EXAMPLE = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "We couldn't record this change, so it wasn't made. Try again.",
              "data": { "errorCode": "audit_unavailable" }
            }
            """;

    static final String DOMAINS_UNCONFIGURED_EXAMPLE = """
            {
              "code": "503 SERVICE_UNAVAILABLE",
              "message": "Staff accounts aren't set up on this server yet.",
              "data": { "errorCode": "staff_domains_unconfigured" }
            }
            """;

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_READ + "')")
    @Operation(summary = "List all roles",
            description = "Built-in roles first, then custom roles, each alphabetical.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles listed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Roles retrieved",
                                      "data": [
                                        {
                                          "name": "CALL_CENTER_AGENT",
                                          "description": "Call-center agent: looks customers up and performs routine support actions.",
                                          "builtin": true,
                                          "permissions": ["device-security:manage", "device-security:read"],
                                          "createdBy": "flyway:V43",
                                          "createdAt": "2026-09-30T06:00:00Z",
                                          "updatedAt": null,
                                          "staffRole": true
                                        },
                                        {
                                          "name": "MERCHANT_ADMIN",
                                          "description": "Runs a loyalty merchant; manages that merchant's shops and rules.",
                                          "builtin": true,
                                          "permissions": ["shop-admins:write", "shop-staff:merchant:read", "shop-staff:password:reset", "shop-staff:read"],
                                          "createdBy": null,
                                          "createdAt": "2026-09-02T14:00:00Z",
                                          "updatedAt": null,
                                          "staffRole": false
                                        },
                                        {
                                          "name": "REFUND_OFFICER",
                                          "description": "Handles customer refund requests and can reset staff passwords.",
                                          "builtin": false,
                                          "permissions": ["users:password:reset", "users:read"],
                                          "createdBy": "admin@innbucks.co.zw",
                                          "createdAt": "2026-09-02T14:31:00Z",
                                          "updatedAt": null,
                                          "staffRole": true
                                        }
                                      ]
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Caller lacks roles:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<RoleDTOs.RoleResponse>>> list() {
        List<RoleDTOs.RoleResponse> roles = roleAdminService.list().stream()
                .map(RoleDTOs.RoleResponse::of)
                .toList();
        return ResponseEntity.ok(ApiResult.ok("Roles retrieved", roles));
    }

    @GetMapping("/{name}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_READ + "')")
    @Operation(summary = "Read one role")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Role retrieved",
                                      "data": {
                                        "name": "REFUND_OFFICER",
                                        "description": "Handles customer refund requests and can reset staff passwords.",
                                        "builtin": false,
                                        "permissions": ["users:password:reset", "users:read"],
                                        "createdBy": "admin@innbucks.co.zw",
                                        "createdAt": "2026-09-02T14:31:00Z",
                                        "updatedAt": null,
                                        "staffRole": true
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such role",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Role not found: REFUND_OFICER", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<RoleDTOs.RoleResponse>> get(@PathVariable String name) {
        return ResponseEntity.ok(ApiResult.ok("Role retrieved",
                RoleDTOs.RoleResponse.of(roleAdminService.get(name))));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_WRITE + "')")
    @Operation(summary = "Create a role",
            description = """
                    Creates a role as a named bundle of EXISTING permissions. The role is usable \
                    immediately — assign it with `PUT /admin/users/{id}/roles` and the holder's next \
                    token carries its permissions.

                    Permissions cannot be invented here: every code must already appear in \
                    `GET /admin/roles/permissions`, because a permission is only real when a \
                    `@PreAuthorize` somewhere names it. Granting a code nothing enforces would \
                    produce a role that looks capable and is not.

                    **No escalation.** Every code must be one the CALLER holds, read from their \
                    current roles (not their token) — `400 permission_not_assignable`, reason \
                    `exceeds_your_authority`. The codes that hand out authority — `roles:write`, \
                    `users:roles:write`, `staff:read`, `staff:create`, `staff:manage` and \
                    `organizations:manage` — are reserved to SUPER_ADMIN and never accepted, whoever \
                    asks: reason `reserved_to_super_admin`. `data.codes` names every refused code with \
                    its reason.

                    **Staff holders.** `user_roles` has no foreign key to `roles`, so accounts may \
                    already hold the new name. If the new role is a staff role (it holds a PLATFORM \
                    permission), every such holder must be staff-eligible — an InnBucks address proven \
                    by a staff invite — or `400 staff_holders_ineligible` (count, count per reason, up \
                    to 20 holders' userUuids).

                    The `*` wildcard is rejected — a role holding it would be equivalent to \
                    SUPER_ADMIN, which is the same escalation `PUT /admin/users/{id}/roles` refuses \
                    when it blocks granting SUPER_ADMIN directly.

                    **Reserved names:** `ADMIN` (never a platform role), a bare `CALL_CENTER` and any \
                    `CALL_CENTRE…` spelling (the call-center roles are built in). The built-in names \
                    themselves are taken (409).

                    **Audited or not made:** if the `ROLE_CREATED` audit row cannot be written the \
                    role is not created — `503 audit_unavailable`; retry later.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role created",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Role created",
                                      "data": {
                                        "name": "REFUND_OFFICER",
                                        "description": "Handles customer refund requests and can reset staff passwords.",
                                        "builtin": false,
                                        "permissions": ["users:password:reset", "users:read"],
                                        "createdBy": "admin@innbucks.co.zw",
                                        "createdAt": "2026-09-02T14:31:00Z",
                                        "updatedAt": null,
                                        "staffRole": true
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400",
                    description = "Malformed or reserved name, no permissions, a permission that does not exist, "
                            + "or one the caller may not grant",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Holders not staff-eligible", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Some accounts holding this role haven't confirmed an InnBucks email. Invite or remove them first.",
                                              "data": {
                                                "errorCode": "staff_holders_ineligible",
                                                "ineligibleHolders": 2,
                                                "byReason": { "no_profile": 1, "off_domain": 1 },
                                                "sample": ["2b9d4c1a-7e3f-4a6b-8d2c-5f1e0a9b3c47", "5d0f3e2a-1b4c-4d6e-9f8a-7c6b5a4d3e21"]
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Reserved to SUPER_ADMIN", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These permissions can't be granted here: roles:write (reserved to SUPER_ADMIN).",
                                              "data": {
                                                "errorCode": "permission_not_assignable",
                                                "codes": { "roles:write": "reserved_to_super_admin" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Beyond the caller's own permissions", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These permissions can't be granted here: device-security:fraud (grants more than you hold).",
                                              "data": {
                                                "errorCode": "permission_not_assignable",
                                                "codes": { "device-security:fraud": "exceeds_your_authority" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Unknown permission", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Unknown permission(s): refunds:approve. Permissions are defined in code, not created through the API — list what exists with GET /admin/roles/permissions.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Reserved name", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "The role name ADMIN is reserved: it has never been a platform role, so a role called that would read as authority it does not have. Use a built-in role (PRODUCT_OFFICER, PRODUCT_MANAGER, CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, FRAUD_DESK) or choose another name.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Bad name", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Role name must be UPPER_SNAKE_CASE, 2-64 characters, starting with a letter (e.g. REFUND_OFFICER). Got: refund officer",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Validation failed", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "permissions": "permissions must contain at least one permission" }
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "403",
                    description = "Caller lacks roles:write, or tried to grant the '*' wildcard",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Wildcard refused", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "The '*' permission cannot be granted through this endpoint — it would make the role equivalent to SUPER_ADMIN. Grant the specific permissions the role needs instead.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """)
                            })),
            @ApiResponse(responseCode = "409", description = "A role with that name already exists",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "409 CONFLICT", "message": "A role named REFUND_OFFICER already exists.", "data": null }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The ROLE_CREATED audit row could not be written "
                    + "(the role was not created), or a staff role with holders on a cell with no staff domain",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE_EXAMPLE),
                                    @ExampleObject(name = "No staff domain configured", value = DOMAINS_UNCONFIGURED_EXAMPLE)
                            }))
    })
    public ResponseEntity<ApiResult<RoleDTOs.RoleResponse>> create(
            @Valid @RequestBody RoleDTOs.CreateRoleRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String adminEmail = authentication.getName();
        Role role = roleAdminService.create(request.getName(), request.getDescription(),
                request.getPermissions(), adminEmail, auditContext(httpRequest));

        log.info("POST /admin/roles by={} name={}", adminEmail, role.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.of(HttpStatus.CREATED, "Role created", RoleDTOs.RoleResponse.of(role)));
    }

    @PutMapping("/{name}/permissions")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_WRITE + "')")
    @Operation(summary = "Replace a role's permissions",
            description = """
                    A REPLACE, not a merge — send every permission the role should keep.

                    Allowed on built-in roles: adjusting what MERCHANT_ADMIN can do is a normal \
                    operation, and only a built-in's NAME is depended on by code. The one exception \
                    is SUPER_ADMIN, which must keep `*`.

                    **No escalation.** Every code ADDED must be one the caller holds (read from their \
                    current roles) and must not be reserved to SUPER_ADMIN (`roles:write`, \
                    `users:roles:write`) — otherwise `400 permission_not_assignable` naming each code. \
                    **A business built-in** (EVENT_ORGANIZER, MERCHANT_ADMIN, SHOP_ADMIN, SHOP_USER, \
                    TEAM_MEMBER, CUSTOMER) **never takes a PLATFORM code**, whoever asks (reason \
                    `business_role`): registration, shop-staff and team-member create and the customer \
                    sign-ups hand those roles out with no staff check, so one could never become a staff role. \
                    **Removing codes is never refused on those grounds**, including on a role that \
                    still holds a code reserved today — but a role must keep at least one code \
                    (`400`, "permissions must contain at least one permission…"); delete the role \
                    instead.

                    **When holders see it.** Removing a **PLATFORM** code (see `scope` on \
                    `GET /admin/roles/permissions`) signs every holder of the role out at once: their \
                    `tokenVersion` is bumped in one statement and published fleet-wide after commit, \
                    so their next request answers `401 SESSION_SUPERSEDED` and they refresh or sign \
                    in again. Removing only **TENANT** codes, or adding any, reaches holders when \
                    their token is next minted (next `POST /auth/refresh` — at most the 15-minute \
                    access-token lifetime), so trimming a business role's TENANT codes does not sign \
                    every business out at once. **The sign-out is sized by the role's holders**: \
                    removing a PLATFORM code — or a code the catalog no longer defines, which counts \
                    as PLATFORM — from a widely held business role such as `MERCHANT_ADMIN` signs \
                    every business out at once.

                    **Staff holders.** ADDING a PLATFORM permission (or turning a business role into a \
                    staff role) hands every current holder platform authority, so every holder but \
                    SUPER_ADMIN must be staff-eligible — an InnBucks address proven by a staff invite — \
                    or `400 staff_holders_ineligible` with `ineligibleHolders` (count), `byReason` and \
                    `sample` (up to 20 userUuids): adopt or remove them first. Removing codes is never \
                    refused on these grounds.

                    **Audited or not made:** if the `ROLE_PERMISSIONS_CHANGED` audit row cannot be \
                    written, nothing changes — `503 audit_unavailable`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permissions replaced",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Role permissions updated",
                                      "data": {
                                        "name": "REFUND_OFFICER",
                                        "description": "Handles customer refund requests and can reset staff passwords.",
                                        "builtin": false,
                                        "permissions": ["service-requests:read", "users:password:reset", "users:read"],
                                        "createdBy": "admin@innbucks.co.zw",
                                        "createdAt": "2026-09-02T14:31:00Z",
                                        "updatedAt": "2026-09-02T15:02:00Z",
                                        "staffRole": true
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400",
                    description = "No permissions, one that does not exist, or an added one the caller may not grant",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Holders not staff-eligible", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Some accounts holding this role haven't confirmed an InnBucks email. Invite or remove them first.",
                                              "data": {
                                                "errorCode": "staff_holders_ineligible",
                                                "ineligibleHolders": 2,
                                                "byReason": { "no_profile": 1, "off_domain": 1 },
                                                "sample": ["2b9d4c1a-7e3f-4a6b-8d2c-5f1e0a9b3c47", "5d0f3e2a-1b4c-4d6e-9f8a-7c6b5a4d3e21"]
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Reserved to SUPER_ADMIN", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These permissions can't be granted here: users:roles:write (reserved to SUPER_ADMIN).",
                                              "data": {
                                                "errorCode": "permission_not_assignable",
                                                "codes": { "users:roles:write": "reserved_to_super_admin" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Beyond the caller's own permissions", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These permissions can't be granted here: service-requests:approve (grants more than you hold).",
                                              "data": {
                                                "errorCode": "permission_not_assignable",
                                                "codes": { "service-requests:approve": "exceeds_your_authority" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "A platform code on a business role", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "These permissions can't be granted here: users:read (a business role can't hold platform permissions).",
                                              "data": {
                                                "errorCode": "permission_not_assignable",
                                                "codes": { "users:read": "business_role" }
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Empty permission set", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Validation failed",
                                              "data": { "permissions": "permissions must contain at least one permission" }
                                            }
                                            """),
                                    @ExampleObject(name = "Only blank entries", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "permissions must contain at least one permission. A role granting nothing is assignable but authorizes for nothing; list the available permissions with GET /admin/roles/permissions.",
                                              "data": null
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "403",
                    description = "Caller lacks roles:write, tried to grant '*', or tried to narrow SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "SUPER_ADMIN narrowed", value = """
                                            {
                                              "code": "403 FORBIDDEN",
                                              "message": "SUPER_ADMIN must keep the '*' permission — narrowing it would lock the platform out of its own role administration.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Missing permission", value = """
                                            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                            """)
                            })),
            @ApiResponse(responseCode = "404", description = "No such role",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Role not found: REFUND_OFICER", "data": null }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The ROLE_PERMISSIONS_CHANGED audit row could not be "
                    + "written (nothing changed), or a platform permission was added to a held role on a cell "
                    + "with no staff domain",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Audit unavailable", value = AUDIT_UNAVAILABLE_EXAMPLE),
                                    @ExampleObject(name = "No staff domain configured", value = DOMAINS_UNCONFIGURED_EXAMPLE)
                            }))
    })
    public ResponseEntity<ApiResult<RoleDTOs.RoleResponse>> setPermissions(
            @PathVariable String name,
            @Valid @RequestBody RoleDTOs.SetRolePermissionsRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String adminEmail = authentication.getName();
        Role role = roleAdminService.setPermissions(name, request.getPermissions(),
                adminEmail, auditContext(httpRequest));

        log.info("PUT /admin/roles/{}/permissions by={}", role.getName(), adminEmail);
        return ResponseEntity.ok(ApiResult.ok("Role permissions updated", RoleDTOs.RoleResponse.of(role)));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_WRITE + "')")
    @Operation(summary = "Delete a custom role",
            description = """
                    Refused for a built-in role (code references those by name) and refused while any \
                    account still holds the role — a deleted role would leave its holders \
                    authenticating normally while silently losing everything it granted, with no \
                    error to explain it. Reassign the holders first.

                    **Audited or not made:** if the `ROLE_DELETED` audit row cannot be written the \
                    role is kept — `503 audit_unavailable`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role deleted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "200 OK", "message": "Role deleted", "data": null }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Caller lacks roles:write, or the role is built-in",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Built-in", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "Built-in roles cannot be deleted. MERCHANT_ADMIN is referenced by name in code (authorization checks, service-bundle mapping, the admin seed), so removing the row would break those silently rather than loudly. Remove its permissions instead if you want it to grant nothing.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such role",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Role not found: REFUND_OFICER", "data": null }
                                    """))),
            @ApiResponse(responseCode = "409", description = "Accounts still hold the role",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "Role REFUND_OFFICER is still assigned to 3 account(s). Reassign them with PUT /admin/users/{id}/roles before deleting it.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "The ROLE_DELETED audit row could not be written; "
                    + "the role was not deleted",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = AUDIT_UNAVAILABLE_EXAMPLE)))
    })
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable String name,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String adminEmail = authentication.getName();
        roleAdminService.delete(name, adminEmail, auditContext(httpRequest));

        log.info("DELETE /admin/roles/{} by={}", name, adminEmail);
        return ResponseEntity.ok(ApiResult.ok("Role deleted", null));
    }

    /**
     * The permission catalog. Lives on this controller rather than its own
     * {@code /admin/permissions} mapping so that one gateway route
     * ({@code /admin/roles/**}) covers the whole feature — a separate top-level
     * path would need its own route, and per this repo's gateway rule an
     * unrouted path is a 404 through the edge no matter what the service serves.
     */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('" + PermissionCatalog.ROLES_READ + "')")
    @Operation(summary = "List the permissions a role can be composed from",
            description = """
                    The catalog is defined in code and cannot be added to through the API — a \
                    permission only means anything because an authorization check names it, so one \
                    created at runtime would grant nothing. Adding a permission is a code change \
                    plus a deploy.

                    `*` appears here because SUPER_ADMIN holds it, but it cannot be granted to a \
                    role you create.

                    Each code carries its `scope` — `PLATFORM` (acts across every business: a role \
                    holding one is a staff role, and removing one from a role signs its holders out \
                    at once) or `TENANT` (acts inside the caller's own business) — and \
                    `reservedToSuperAdmin`, true for the codes no role can be given through the API. \
                    A role picker should grey out reserved codes and any code the signed-in \
                    administrator does not hold (their `permissions` on the sign-in response).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Catalog listed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Permissions retrieved",
                                      "data": [
                                        { "code": "*", "description": "Every permission, including ones added by future releases. Reserved for SUPER_ADMIN.", "scope": "PLATFORM", "reservedToSuperAdmin": true },
                                        { "code": "users:read", "description": "List and read any user account", "scope": "PLATFORM", "reservedToSuperAdmin": false },
                                        { "code": "users:roles:write", "description": "Replace the role set on a user account", "scope": "PLATFORM", "reservedToSuperAdmin": true },
                                        { "code": "roles:write", "description": "Create, edit and delete custom roles", "scope": "PLATFORM", "reservedToSuperAdmin": true },
                                        { "code": "team-members:read", "description": "Read an event organizer's team members", "scope": "TENANT", "reservedToSuperAdmin": false },
                                        { "code": "device-security:read", "description": "Look up a customer's phones, blocks, references and sign-in history", "scope": "PLATFORM", "reservedToSuperAdmin": false }
                                      ]
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Caller lacks roles:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<RoleDTOs.PermissionResponse>>> permissions() {
        return ResponseEntity.ok(ApiResult.ok("Permissions retrieved",
                RoleDTOs.PermissionResponse.of(roleAdminService.permissionCatalog())));
    }

    private static AuditContext auditContext(HttpServletRequest request) {
        return new AuditContext(clientIp(request), request.getHeader("User-Agent"));
    }

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
