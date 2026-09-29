package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request and response shapes for the role administration API (V35). */
public final class RoleDTOs {

    @Data
    @Schema(name = "CreateRoleRequest",
            description = "Create a role as a named bundle of EXISTING permissions. Permissions "
                    + "themselves are defined in code and cannot be created here — list what is "
                    + "available with GET /admin/roles/permissions. Every code must be one the caller "
                    + "holds, and codes marked reservedToSuperAdmin are never accepted.")
    public static class CreateRoleRequest {

        @NotBlank(message = "name is required")
        @Schema(example = "REFUND_OFFICER",
                description = "UPPER_SNAKE_CASE, 2-64 characters, starting with a letter. The name "
                        + "becomes a Spring Security authority (ROLE_<name>) and travels in the JWT, "
                        + "so anything else would save and then never match.")
        private String name;

        @NotBlank(message = "description is required")
        @Size(max = 500)
        @Schema(example = "Handles customer refund requests and can reset staff passwords.",
                description = "What the role is for. Shown in GET /admin/roles; required because a "
                        + "list of bare names is unusable six months later.")
        private String description;

        @NotEmpty(message = "permissions must contain at least one permission")
        @Schema(example = "[\"users:read\", \"users:password:reset\"]",
                description = "Permission codes from GET /admin/roles/permissions. The '*' wildcard is "
                        + "rejected — granting it would make the role equivalent to SUPER_ADMIN — and so is "
                        + "any code reserved to SUPER_ADMIN (roles:write, users:roles:write) or not held by "
                        + "the caller (400 permission_not_assignable).")
        private Set<String> permissions;
    }

    @Data
    @Schema(name = "SetRolePermissionsRequest",
            description = "Replace a role's permission set. This is a REPLACE, not a merge — send "
                    + "every permission the role should keep. Codes ADDED must be held by the caller and "
                    + "not reserved to SUPER_ADMIN; removing codes is never refused on those grounds, "
                    + "but a role must keep at least one code.")
    public static class SetRolePermissionsRequest {

        @NotEmpty(message = "permissions must contain at least one permission")
        @Schema(example = "[\"users:read\", \"users:password:reset\", \"service-requests:read\"]")
        private Set<String> permissions;
    }

    @Schema(name = "RoleResponse")
    public record RoleResponse(
            @Schema(example = "REFUND_OFFICER") String name,
            @Schema(example = "Handles customer refund requests and can reset staff passwords.")
            String description,
            @Schema(example = "false",
                    description = "True for the roles that ship with the platform (the nine V35 seeded "
                            + "plus CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR and FRAUD_DESK). Built-ins "
                            + "cannot be deleted or renamed because code references them by name; "
                            + "their permissions are still editable.")
            boolean builtin,
            @Schema(example = "[\"users:password:reset\", \"users:read\"]")
            List<String> permissions,
            @Schema(example = "admin@innbucks.co.zw", nullable = true,
                    description = "Null for the V35 built-ins; `flyway:V43` for the call-center built-ins.")
            String createdBy,
            @Schema(example = "2026-09-02T14:31:00Z") Instant createdAt,
            @Schema(example = "2026-09-02T15:02:00Z", nullable = true) Instant updatedAt,
            @Schema(example = "true",
                    description = "True for a STAFF role: a platform staff built-in (SUPER_ADMIN, "
                            + "PRODUCT_OFFICER, PRODUCT_MANAGER, CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, "
                            + "FRAUD_DESK), or any role holding a PLATFORM permission. Staff roles reset "
                            + "passwords by email only, and switching off one of their holders needs the "
                            + "caller to hold everything that holder holds (and, for a built-in, that role).")
            boolean staffRole) {

        public static RoleResponse of(Role role) {
            return new RoleResponse(
                    role.getName(),
                    role.getDescription(),
                    role.isBuiltin(),
                    role.getPermissions() == null ? List.of()
                            : role.getPermissions().stream().sorted().toList(),
                    role.getCreatedBy(),
                    role.getCreatedAt(),
                    role.getUpdatedAt(),
                    com.innbucks.userservice.security.StaffRoles.isStaffRole(role));
        }
    }

    @Schema(name = "PermissionResponse")
    public record PermissionResponse(
            @Schema(example = "users:read") String code,
            @Schema(example = "List and read any user account") String description,
            @Schema(example = "PLATFORM", allowableValues = {"PLATFORM", "TENANT"},
                    description = "PLATFORM: acts across every business on the platform — a role holding "
                            + "one is a staff role, and removing one from a role signs its holders out at "
                            + "once. TENANT: acts only inside the caller's own business.")
            String scope,
            @Schema(example = "false",
                    description = "True for the codes only SUPER_ADMIN holds (through `*`): they can never "
                            + "be granted to any role through the API. `*` itself is also never grantable.")
            boolean reservedToSuperAdmin) {

        public static List<PermissionResponse> of(Map<String, String> catalog) {
            return catalog.entrySet().stream()
                    .map(e -> new PermissionResponse(e.getKey(), e.getValue(),
                            com.innbucks.userservice.security.PermissionCatalog.scopeOf(e.getKey()).name(),
                            com.innbucks.userservice.security.PermissionCatalog.isReservedToWildcard(e.getKey())
                                    || com.innbucks.userservice.security.PermissionCatalog.WILDCARD.equals(e.getKey())))
                    .toList();
        }
    }

    private RoleDTOs() {}
}
