package com.innbucks.userservice.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * carry a foreign key and so {@code GET /admin/roles/permissions} can tell an operator
 * what is available to compose with.
 *
 * <p><b>When you add a permission here you must also grant it to whichever
 * built-in roles should hold it</b>, in a migration. The one role you never need
 * to touch is {@code SUPER_ADMIN}: it holds {@link #WILDCARD}, which
 * {@link PermissionResolver} expands against this catalog at token-mint time, so
 * the platform owner picks up new permissions automatically. Enumerating them
 * for SUPER_ADMIN instead would silently lock the owner out of every endpoint
 * added after the enumeration.
 *
 * <p><b>Every entry declares a {@link Scope}</b> ({@code PLATFORM} or
 * {@code TENANT}), as a required argument — see the enum for what each means and
 * what reads it. A new code that hands out authority itself (the power to edit
 * roles, assign them, or administer staff) also belongs in
 * {@link #WILDCARD_RESERVED}, which keeps it out of every role but SUPER_ADMIN's.
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

    public static final String ORGANIZATIONS_READ = "organizations:read";
    /** Suspend an organization (V44). Reserved to the wildcard. */
    public static final String ORGANIZATIONS_MANAGE = "organizations:manage";

    /**
     * Staff accounts (V44): the directory and audit, creating and adopting staff,
     * and deactivating / reactivating them. All three are reserved to the
     * wildcard ({@link #WILDCARD_RESERVED}) — staff administration stays with
     * SUPER_ADMIN until the owner decides otherwise.
     */
    public static final String STAFF_READ = "staff:read";
    public static final String STAFF_CREATE = "staff:create";
    public static final String STAFF_MANAGE = "staff:manage";

    /**
     * DTX device security (V40) — the call center's and fraud desk's tools.
     * Three tiers on purpose: an agent who can look a caller up need not be able
     * to unlock their phone, and the call center must not be able to lift a
     * fraud-desk ban on a caller's say-so.
     */
    public static final String DEVICE_SECURITY_READ = "device-security:read";
    public static final String DEVICE_SECURITY_MANAGE = "device-security:manage";
    public static final String DEVICE_SECURITY_FRAUD = "device-security:fraud";

    /**
     * Customer support for the marketplace and loyalty products (V47). These are
     * ENFORCED IN OTHER SERVICES — marketplace-service ({@code /marketplace/support/**})
     * and loyalty-service ({@code /loyalty/support/**}) read the {@code perms} claim
     * and gate on {@code hasAuthority}. Nothing in user-service checks them; they
     * live here because this catalog is the fleet's one permission vocabulary and
     * {@code role_permissions} can only reference a code it defines.
     *
     * <p>Three tiers per product, the device-security split again: {@code read}
     * looks a customer up, {@code manage} performs routine actions on their behalf,
     * {@code supervise} holds the actions that move money or override a block, and
     * reviews every agent's support activity. {@link #CUSTOMER_MESSAGES_SEND} is one
     * code for both products: typing a message to a customer is the same power
     * whichever product the customer came through, and the owner grants it once.
     */
    public static final String MARKETPLACE_SUPPORT_READ = "marketplace-support:read";
    public static final String MARKETPLACE_SUPPORT_MANAGE = "marketplace-support:manage";
    public static final String MARKETPLACE_SUPPORT_SUPERVISE = "marketplace-support:supervise";
    public static final String LOYALTY_SUPPORT_READ = "loyalty-support:read";
    public static final String LOYALTY_SUPPORT_MANAGE = "loyalty-support:manage";
    public static final String LOYALTY_SUPPORT_SUPERVISE = "loyalty-support:supervise";
    public static final String CUSTOMER_MESSAGES_SEND = "customer-messages:send";

    /**
     * Unified customer support (V46) — the Foundry console section of
     * {@code /admin/support/**}. Tiered like device security: an agent reads and
     * does the routine things (unlock, send a reset code); resetting a second
     * factor is the classic help-desk takeover step (stolen password + a phone
     * call), so it is a SUPERVISOR code of its own. {@code support-staff-targets:manage}
     * is what lets a supervisor act at all when a lookup matches an InnBucks
     * staff account. None is wildcard-reserved: they are granted to the
     * CALL_CENTER built-ins by migration, and an operator may compose them.
     */
    public static final String SUPPORT_CONSOLE_READ = "support-console:read";
    public static final String SUPPORT_CONSOLE_MANAGE = "support-console:manage";
    public static final String SUPPORT_CONSOLE_MFA_RESET = "support-console:mfa:reset";
    public static final String SUPPORT_STAFF_TARGETS_MANAGE = "support-staff-targets:manage";

    /**
     * Who a permission's authority reaches — the half of a permission's meaning
     * the no-escalation and staff rules read.
     *
     * <ul>
     *   <li>{@link #PLATFORM}: acts across every business on the platform (any
     *       user account, every role, every organization, any customer's phone).
     *       Holding one makes a role a STAFF role ({@link StaffRoles}), and taking
     *       one away from a role ends its holders' sessions at once
     *       ({@code RoleAdminService.setPermissions}).</li>
     *   <li>{@link #TENANT}: acts only inside the caller's own business — its
     *       team members, its shops, its shop staff. The handlers scope every
     *       call to the caller's own organizer, merchant or shop.</li>
     * </ul>
     *
     * <p>A <b>required</b> argument of {@link #entry}, so no permission can be
     * added without someone deciding which it is. There is deliberately no
     * default: a forgotten scope would silently read as whichever the default
     * was, and one of the two is always the dangerous answer.
     */
    public enum Scope { PLATFORM, TENANT }

    /** One catalog row: the code, the text an operator reads, and its {@link Scope}. */
    public record Entry(String code, String description, Scope scope) {
        public Entry {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description is required for " + code);
            }
            if (scope == null) throw new IllegalArgumentException("scope is required for " + code);
        }
    }

    private static Entry entry(String code, String description, Scope scope) {
        return new Entry(code, description, scope);
    }

    /**
     * Every permission this service defines, in declaration order, with its
     * {@link Scope}. Ordered (LinkedHashMap) so the listing groups related codes
     * together rather than arriving in hash order.
     *
     * <p>{@link #WILDCARD} is included: it is a grantable permission (that is how
     * SUPER_ADMIN holds it) and an operator building a role needs to see it
     * exists. {@link #concrete()} is the set to expand it to.
     */
    public static final Map<String, Entry> ENTRIES;

    /**
     * {@link #ENTRIES} as code → description — the shape
     * {@code GET /admin/roles/permissions} and {@link PermissionCatalogInitializer}
     * read.
     */
    public static final Map<String, String> ALL;

    static {
        Map<String, Entry> all = new LinkedHashMap<>();
        for (Entry e : List.of(
                entry(WILDCARD, "Every permission, including ones added by future releases. Reserved for SUPER_ADMIN.",
                        Scope.PLATFORM),
                entry(USERS_READ, "List and read any user account", Scope.PLATFORM),
                entry(USERS_MERCHANTS_READ, "List merchant and organizer accounts", Scope.PLATFORM),
                entry(USERS_ACTIVATION_WRITE,
                        "Activate or deactivate a user account, and approve or reject a pending registration",
                        Scope.PLATFORM),
                entry(USERS_ROLES_WRITE, "Replace the role set on a user account", Scope.PLATFORM),
                entry(USERS_MFA_RESET, "Reset a user's MFA enrolment", Scope.PLATFORM),
                entry(USERS_PASSWORD_RESET, "Issue a temporary password for a user account", Scope.PLATFORM),
                entry(ROLES_READ, "List roles and the available permission catalog", Scope.PLATFORM),
                entry(ROLES_WRITE, "Create, edit and delete custom roles", Scope.PLATFORM),
                entry(SERVICE_REQUESTS_READ, "List submitted service-bundle requests", Scope.PLATFORM),
                entry(SERVICE_REQUESTS_APPROVE, "Approve a service-bundle request", Scope.PLATFORM),
                entry(TEAM_MEMBERS_READ, "Read an event organizer's team members", Scope.TENANT),
                entry(TEAM_MEMBERS_WRITE, "Create an event organizer team member", Scope.TENANT),
                entry(TEAM_MEMBERS_MANAGE, "Enable, delete, reset or re-scope a team member", Scope.TENANT),
                entry(SHOP_ADMINS_WRITE, "Create a shop admin under a loyalty merchant", Scope.TENANT),
                entry(SHOP_USERS_WRITE, "Create shop POS users, individually or by CSV upload", Scope.TENANT),
                entry(SHOP_STAFF_READ, "Read the shop staff of your own shop or merchant", Scope.TENANT),
                entry(SHOP_STAFF_MERCHANT_READ, "Read shop staff across any shop of a merchant", Scope.TENANT),
                entry(SHOP_STAFF_PASSWORD_RESET, "Issue a temporary password for a shop staff account",
                        Scope.TENANT),
                entry(ORGANIZATIONS_READ, "List every organization on the platform", Scope.PLATFORM),
                entry(ORGANIZATIONS_MANAGE, "Suspend an organization", Scope.PLATFORM),
                entry(STAFF_READ, "View staff accounts and their audit history", Scope.PLATFORM),
                entry(STAFF_CREATE, "Invite new staff, adopt existing staff, resend invites", Scope.PLATFORM),
                entry(STAFF_MANAGE, "Deactivate and reactivate staff accounts", Scope.PLATFORM),
                entry(DEVICE_SECURITY_READ,
                        "Look up a customer's phones, blocks, references and sign-in history", Scope.PLATFORM),
                entry(DEVICE_SECURITY_MANAGE,
                        "Block, unlock, remove or reset a customer's phone, and cancel open codes", Scope.PLATFORM),
                entry(DEVICE_SECURITY_FRAUD, "Ban a phone for fraud (including every account on it), lift such bans, "
                        + "and fraud-flag a customer", Scope.PLATFORM),
                entry(MARKETPLACE_SUPPORT_READ, "Look up marketplace buyers, orders, parcels and sellers, "
                        + "with their support notes and messages", Scope.PLATFORM),
                entry(MARKETPLACE_SUPPORT_MANAGE, "Routine marketplace support: notes, resend notices and "
                        + "collection codes, cancel an unpaid order, open a dispute for a buyer", Scope.PLATFORM),
                entry(MARKETPLACE_SUPPORT_SUPERVISE, "Cancel a paid, undispatched parcel for a buyer (refund "
                        + "queued) and review every agent's marketplace support activity", Scope.PLATFORM),
                entry(LOYALTY_SUPPORT_READ, "Look up a loyalty customer across every tenant: balance, "
                        + "history, vouchers, orders, notes and messages", Scope.PLATFORM),
                entry(LOYALTY_SUPPORT_MANAGE, "Routine loyalty support: notes, resend a voucher to its holder, "
                        + "sign a customer out everywhere", Scope.PLATFORM),
                entry(LOYALTY_SUPPORT_SUPERVISE, "Adjust or reverse a customer's points, unblock a membership, "
                        + "and review every agent's loyalty support activity", Scope.PLATFORM),
                entry(CUSTOMER_MESSAGES_SEND, "Type and send an SMS or WhatsApp message to a customer on "
                        + "record from a support screen", Scope.PLATFORM),
                entry(SUPPORT_CONSOLE_READ, "Look customers up in customer support and see their Foundry console "
                        + "account: status, roles, organizations, 2FA, lockout and service requests", Scope.PLATFORM),
                entry(SUPPORT_CONSOLE_MANAGE, "Unlock a Foundry console account and send it a password-reset code "
                        + "from customer support", Scope.PLATFORM),
                entry(SUPPORT_CONSOLE_MFA_RESET, "Reset a Foundry console account's two-factor sign-in from customer "
                        + "support (supervisor)", Scope.PLATFORM),
                entry(SUPPORT_STAFF_TARGETS_MANAGE, "Act in customer support when a lookup matches an InnBucks staff "
                        + "account (supervisor)", Scope.PLATFORM))) {
            if (all.put(e.code(), e) != null) {
                throw new IllegalStateException("Duplicate permission code in the catalog: " + e.code());
            }
        }
        ENTRIES = Collections.unmodifiableMap(all);

        Map<String, String> descriptions = new LinkedHashMap<>();
        all.forEach((code, e) -> descriptions.put(code, e.description()));
        ALL = Collections.unmodifiableMap(descriptions);
    }

    /**
     * Codes only {@link #WILDCARD} may hold. They can NEVER be granted through the
     * API — not to a custom role, not to a built-in, not even by SUPER_ADMIN —
     * because each is the power to hand out authority itself:
     *
     * <ul>
     *   <li>{@code roles:write} — edit any role, including one you hold, so a
     *       holder could add every other permission to their own role;</li>
     *   <li>{@code users:roles:write} — put any role on any account, including
     *       your own;</li>
     *   <li>{@code staff:read}, {@code staff:create}, {@code staff:manage},
     *       {@code organizations:manage} — staff administration and suspending a
     *       business, which stay with SUPER_ADMIN until the owner decides
     *       otherwise. They were reserved one release before the endpoints that
     *       enforce them ({@code /admin/staff/**},
     *       {@code POST /admin/organizations/{id}/suspend}) existed, so no role
     *       could be handed them ahead of time.</li>
     * </ul>
     *
     * SUPER_ADMIN holds them through its wildcard. Only a reviewed migration can
     * grant one to a built-in role, and none does.
     */
    public static final Set<String> WILDCARD_RESERVED = Set.of(
            ROLES_WRITE, USERS_ROLES_WRITE,
            STAFF_READ, STAFF_CREATE, STAFF_MANAGE, ORGANIZATIONS_MANAGE);

    /** Every real permission — {@link #ALL} minus the wildcard. What {@code *} expands to. */
    public static Set<String> concrete() {
        Set<String> codes = new java.util.LinkedHashSet<>(ALL.keySet());
        codes.remove(WILDCARD);
        return Collections.unmodifiableSet(codes);
    }

    public static boolean isKnown(String code) {
        return code != null && ALL.containsKey(code);
    }

    /**
     * The scope of a code. A code the catalog does not define — a stale
     * {@code role_permissions} row for a permission since removed from the code,
     * or anything else unexpected — classifies as {@link Scope#PLATFORM}: the
     * answer that refuses more, so an unknown code can never be what lets a role
     * slip out of the staff rules or skip a session bump.
     */
    public static Scope scopeOf(String code) {
        Entry e = code == null ? null : ENTRIES.get(code);
        return e == null ? Scope.PLATFORM : e.scope();
    }

    /** True for a code only the wildcard may hold — see {@link #WILDCARD_RESERVED}. */
    public static boolean isReservedToWildcard(String code) {
        return code != null && WILDCARD_RESERVED.contains(code);
    }

    private PermissionCatalog() {}
}
