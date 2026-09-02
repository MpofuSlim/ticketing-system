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
             + "permissions they compose are defined in code and listed by GET /admin/permissions.")
@SecurityRequirement(name = "bearerAuth")
public class RoleAdminController {

    private final RoleAdminService roleAdminService;

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
                                          "name": "MERCHANT_ADMIN",
                                          "description": "Runs a loyalty merchant; manages that merchant's shops and rules.",
                                          "builtin": true,
                                          "permissions": ["shop-admins:write", "shop-staff:merchant:read", "shop-staff:password:reset", "shop-staff:read"],
                                          "createdBy": null,
                                          "createdAt": "2026-09-02T14:00:00Z",
                                          "updatedAt": null
                                        },
                                        {
                                          "name": "REFUND_OFFICER",
                                          "description": "Handles customer refund requests and can reset staff passwords.",
                                          "builtin": false,
                                          "permissions": ["users:password:reset", "users:read"],
                                          "createdBy": "admin@innbucks.co.zw",
                                          "createdAt": "2026-09-02T14:31:00Z",
                                          "updatedAt": null
                                        }
                                      ]
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Caller lacks roles:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You don't have permission to do that.", "data": null }
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
                                        "updatedAt": null
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
                    `GET /admin/permissions`, because a permission is only real when a \
                    `@PreAuthorize` somewhere names it. Granting a code nothing enforces would \
                    produce a role that looks capable and is not.

                    The `*` wildcard is rejected — a role holding it would be equivalent to \
                    SUPER_ADMIN, which is the same escalation `PUT /admin/users/{id}/roles` refuses \
                    when it blocks granting SUPER_ADMIN directly.""")
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
                                        "updatedAt": null
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400",
                    description = "Malformed name, no permissions, or a permission that does not exist",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Unknown permission", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Unknown permission(s): refunds:approve. Permissions are defined in code, not created through the API — list what exists with GET /admin/permissions.",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Bad name", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Role name must be UPPER_SNAKE_CASE, 2-64 characters, starting with a letter (e.g. REFUND_OFFICER). Got: refund officer",
                                              "data": null
                                            }
                                            """)
                            })),
            @ApiResponse(responseCode = "403",
                    description = "Caller lacks roles:write, or tried to grant the '*' wildcard",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Wildcard refused", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "The '*' permission cannot be granted through this endpoint — it would make the role equivalent to SUPER_ADMIN. Grant the specific permissions the role needs instead.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "409", description = "A role with that name already exists",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "409 CONFLICT", "message": "A role named REFUND_OFFICER already exists.", "data": null }
                                    """)))
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

                    Holders do not see the change until their token is re-minted (next login or \
                    `POST /auth/refresh`), the same latency the `roles` claim has always had.""")
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
                                        "updatedAt": "2026-09-02T15:02:00Z"
                                      }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "No permissions, or one that does not exist",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "400 BAD_REQUEST",
                                      "message": "permissions must contain at least one permission. A role granting nothing is assignable but authorizes for nothing; list the available permissions with GET /admin/permissions.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "403",
                    description = "Caller lacks roles:write, tried to grant '*', or tried to narrow SUPER_ADMIN",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "SUPER_ADMIN narrowed", value = """
                                    {
                                      "code": "403 FORBIDDEN",
                                      "message": "SUPER_ADMIN must keep the '*' permission — narrowing it would lock the platform out of its own role administration.",
                                      "data": null
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "No such role",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Role not found: REFUND_OFICER", "data": null }
                                    """)))
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
                    error to explain it. Reassign the holders first.""")
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
                                    """)))
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
                    role you create.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Catalog listed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Permissions retrieved",
                                      "data": [
                                        { "code": "*", "description": "Every permission, including ones added by future releases. Reserved for SUPER_ADMIN." },
                                        { "code": "users:read", "description": "List and read any user account" },
                                        { "code": "users:merchants:read", "description": "List merchant and organizer accounts" },
                                        { "code": "users:password:reset", "description": "Issue a temporary password for a user account" },
                                        { "code": "roles:read", "description": "List roles and the available permission catalog" },
                                        { "code": "roles:write", "description": "Create, edit and delete custom roles" }
                                      ]
                                    }
                                    """))),
            @ApiResponse(responseCode = "403", description = "Caller lacks roles:read",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "You don't have permission to do that.", "data": null }
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
