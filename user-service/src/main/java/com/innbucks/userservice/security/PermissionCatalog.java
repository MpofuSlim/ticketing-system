package com.innbucks.userservice.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of capability this service enforces — the single source of
 * truth for what a permission code MEANS.
 *
 * <h2>Why permissions are code and roles are data</h2>
 *
 * A permission is only real because a {@code @PreAuthorize} names it. Inventing
 * one at runtime would produce a string nothing checks, which grants exactly
 * nothing — the same inert-label trap {@code PRODUCT_OFFICER} sat in for months
 * (see {@code User.Role}'s comment: "no @PreAuthorize names them, so a holder is
 * authorized for exactly what a role-less account is"). So there is no endpoint
 * that creates permissions, and adding one is a code change plus a redeploy.
 *
 * <p>A <em>role</em>, by contrast, is just a named bundle of permissions that
 * already exist and are already enforced. Composing a new bundle is useful the
 * instant it is saved, with no deploy. That is why {@code POST /admin/roles}
 * exists and {@code POST /admin/permissions} deliberately does not.
 *
 * <h2>Keeping this in step with the table</h2>
 *
 * {@code permissions} (V35) mirrors this class. {@link PermissionCatalogInitializer}
 * upserts every constant below at boot, so a release that adds a permission here
 * needs no migration of its own. The table exists so {@code role_permissions} can
 * carry a foreign key and so {@code GET /admin/permissions} can tell an operator
 * what is available to compose with.
 *
 * <p><b>When you add a permission here you must also grant it to whichever
 * built-in roles should hold it</b>, in a migration. The one role you never need
 * to touch is {@code SUPER_ADMIN}: it holds {@link #WILDCARD}, which
 * {@link PermissionResolver} expands against this catalog at token-mint time, so
 * the platform owner picks up new permissions automatically. Enumerating them
 * for SUPER_ADMIN instead would silently lock the owner out of every endpoint
 * added after the enumeration.
 */
public final class PermissionCatalog {

    /**
     * Holder of every permission, including ones added by future releases.
     * Reserved for {@code SUPER_ADMIN}. Never emitted into a JWT — the resolver
     * expands it into the concrete set, so {@code hasAuthority} needs no
     * wildcard-aware matching and a token stays self-describing (you can read a
     * token and see exactly what it may do).
     */
    public static final String WILDCARD = "*";

    public static final String USERS_READ = "users:read";
    public static final String USERS_MERCHANTS_READ = "users:merchants:read";
    public static final String USERS_ACTIVATION_WRITE = "users:activation:write";
    public static final String USERS_ROLES_WRITE = "users:roles:write";
    public static final String USERS_MFA_RESET = "users:mfa:reset";
    public static final String USERS_PASSWORD_RESET = "users:password:reset";

    public static final String ROLES_READ = "roles:read";
    public static final String ROLES_WRITE = "roles:write";

    public static final String SERVICE_REQUESTS_READ = "service-requests:read";
    public static final String SERVICE_REQUESTS_APPROVE = "service-requests:approve";

    public static final String TEAM_MEMBERS_READ = "team-members:read";
    public static final String TEAM_MEMBERS_WRITE = "team-members:write";
    public static final String TEAM_MEMBERS_MANAGE = "team-members:manage";

    public static final String SHOP_ADMINS_WRITE = "shop-admins:write";
    public static final String SHOP_USERS_WRITE = "shop-users:write";
    public static final String SHOP_STAFF_READ = "shop-staff:read";
    public static final String SHOP_STAFF_MERCHANT_READ = "shop-staff:merchant:read";
    public static final String SHOP_STAFF_PASSWORD_RESET = "shop-staff:password:reset";

    /**
     * Every permission this service defines, in declaration order, mapped to the
     * description an operator sees in {@code GET /admin/permissions}. Ordered
     * (LinkedHashMap) so the listing groups related codes together rather than
     * arriving in hash order.
     *
     * <p>{@link #WILDCARD} is included: it is a grantable permission (that is how
     * SUPER_ADMIN holds it) and an operator building a role needs to see it
     * exists. {@link #concrete()} is the set to expand it to.
     */
    public static final Map<String, String> ALL;

    static {
        Map<String, String> all = new LinkedHashMap<>();
        all.put(WILDCARD, "Every permission, including ones added by future releases. Reserved for SUPER_ADMIN.");
        all.put(USERS_READ, "List and read any user account");
        all.put(USERS_MERCHANTS_READ, "List merchant and organizer accounts");
        all.put(USERS_ACTIVATION_WRITE, "Activate or deactivate a user account");
        all.put(USERS_ROLES_WRITE, "Replace the role set on a user account");
        all.put(USERS_MFA_RESET, "Reset a user's MFA enrolment");
        all.put(USERS_PASSWORD_RESET, "Issue a temporary password for a user account");
        all.put(ROLES_READ, "List roles and the available permission catalog");
        all.put(ROLES_WRITE, "Create, edit and delete custom roles");
        all.put(SERVICE_REQUESTS_READ, "List submitted service-bundle requests");
        all.put(SERVICE_REQUESTS_APPROVE, "Approve a service-bundle request");
        all.put(TEAM_MEMBERS_READ, "Read an event organizer's team members");
        all.put(TEAM_MEMBERS_WRITE, "Create an event organizer team member");
        all.put(TEAM_MEMBERS_MANAGE, "Enable, delete, reset or re-scope a team member");
        all.put(SHOP_ADMINS_WRITE, "Create a shop admin under a loyalty merchant");
        all.put(SHOP_USERS_WRITE, "Create shop POS users, individually or by CSV upload");
        all.put(SHOP_STAFF_READ, "Read the shop staff of your own shop or merchant");
        all.put(SHOP_STAFF_MERCHANT_READ, "Read shop staff across any shop of a merchant");
        all.put(SHOP_STAFF_PASSWORD_RESET, "Issue a temporary password for a shop staff account");
        ALL = Collections.unmodifiableMap(all);
    }

    /** Every real permission — {@link #ALL} minus the wildcard. What {@code *} expands to. */
    public static Set<String> concrete() {
        Set<String> codes = new java.util.LinkedHashSet<>(ALL.keySet());
        codes.remove(WILDCARD);
        return Collections.unmodifiableSet(codes);
    }

    public static boolean isKnown(String code) {
        return code != null && ALL.containsKey(code);
    }

    private PermissionCatalog() {}
}
