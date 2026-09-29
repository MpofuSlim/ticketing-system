package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.User;

import java.util.Collection;
import java.util.Set;

/**
 * Which roles are STAFF roles — authority over the platform rather than over one
 * business.
 *
 * <p>A role is a staff role when at least one of these holds:
 * <ol>
 *   <li>its name is in {@link #NAMED};</li>
 *   <li>it holds the {@link PermissionCatalog#WILDCARD};</li>
 *   <li>it holds at least one {@link PermissionCatalog.Scope#PLATFORM} permission
 *       (a code the catalog does not define counts as PLATFORM — see
 *       {@link PermissionCatalog#scopeOf}).</li>
 * </ol>
 *
 * <p><b>Why a name list as well as the permission test.</b> booking-service and
 * event-service grant cross-organizer access by role NAME
 * ({@code AuthenticatedCaller.PLATFORM_STAFF_ROLES}, {@code callerMayPublishAnyEvent})
 * — they never read the {@code perms} claim. So {@code PRODUCT_MANAGER} is staff
 * even if an operator empties its permissions here, because holding the name is
 * still authority elsewhere in the fleet. The permission half exists because a
 * custom role is staff by what it can do: a role an operator composes out of
 * {@code users:read} reads every account on the platform whatever it is called.
 *
 * <p>With V35's grants, {@code EVENT_ORGANIZER}, {@code MERCHANT_ADMIN},
 * {@code SHOP_ADMIN}, {@code SHOP_USER}, {@code TEAM_MEMBER} and {@code CUSTOMER}
 * are NOT staff roles. {@code StaffRoleClassificationTest} pins both halves.
 *
 * <p>What reads it: phone-based password reset is a no-op for any holder of a
 * staff role ({@code PasswordResetService}), deactivating one needs the caller
 * to hold everything the target does ({@code UserAdminService.setActive}), and
 * assigning a NAMED one — or removing, deactivating or resetting the 2FA of a
 * holder of one — needs the caller to hold it or the wildcard
 * ({@code RoleGrantGuard}).
 */
public final class StaffRoles {

    /**
     * The staff roles named in code — the platform staff built-ins. Every name
     * here is a {@link User.Role} constant with a {@code builtin = TRUE} row.
     *
     * <p>Assigning one needs the caller to hold that same role or the wildcard,
     * on top of holding every permission it grants: other services act on the
     * NAME, so the permission comparison alone would not bound what it hands out.
     */
    public static final Set<String> NAMED = Set.of(
            User.Role.SUPER_ADMIN.name(),
            User.Role.PRODUCT_OFFICER.name(),
            User.Role.PRODUCT_MANAGER.name(),
            User.Role.CALL_CENTER_AGENT.name(),
            User.Role.CALL_CENTER_SUPERVISOR.name(),
            User.Role.FRAUD_DESK.name());

    private StaffRoles() {}

    /** True when {@code name} is a staff role by name alone. */
    public static boolean isNamed(String name) {
        return name != null && NAMED.contains(name);
    }

    /**
     * True for a staff role, given its name and the permission codes it GRANTS
     * (as stored — not expanded; a stored wildcard or an unknown code both make
     * it staff).
     */
    public static boolean isStaffRole(String name, Collection<String> grantedPermissions) {
        if (isNamed(name)) return true;
        if (grantedPermissions == null) return false;
        for (String code : grantedPermissions) {
            if (PermissionCatalog.WILDCARD.equals(code)) return true;
            if (PermissionCatalog.scopeOf(code) == PermissionCatalog.Scope.PLATFORM) return true;
        }
        return false;
    }

    public static boolean isStaffRole(Role role) {
        return role != null && isStaffRole(role.getName(), role.getPermissions());
    }
}
