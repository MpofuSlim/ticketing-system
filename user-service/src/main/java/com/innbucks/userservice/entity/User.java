package com.innbucks.userservice.entity;

import com.innbucks.userservice.security.MfaSecretConverter;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable, unguessable cross-service identifier for this user. Distinct
     * from {@link #id}, which is the local Postgres PK and used by every
     * internal FK in user-service. {@code user_uuid} is what we expose over
     * the wire — JWT claims, cross-service FKs (events.tenant_user_uuid,
     * booking_items.redeemed_by_user_uuid), and any FE-facing identifier.
     * Auto-generated at INSERT by the DB default ({@code gen_random_uuid()})
     * if the application doesn't pre-populate it; backfilled for legacy rows
     * in the V20 migration.
     */
    @Column(name = "user_uuid", nullable = false, unique = true, updatable = false)
    private UUID userUuid;

    @Column(nullable = false)
    private String firstName;

    private String middleName;

    @Column(nullable = false)
    private String lastName;

    // No `unique = true` here as of step 4 — the actual constraint is
    // composite (phone_number, home_country); see V18 migration. JPA's
    // unique=true is a schema-generation hint only (ignored under
    // ddl-auto: validate), so the change is cosmetic / honest, not
    // load-bearing.
    @Column(nullable = false)
    private String phoneNumber;

    @Column(unique = true)
    private String email;

    /** Free-text registered country (e.g. "Zimbabwe"). Account metadata,
     *  carried into the legacy `country` JWT claim. Distinct from
     *  {@link #homeCountry} below, which is the ISO routing key. */
    private String country;

    /**
     * ISO 3166-1 alpha-2 routing key (e.g. {@code ZW}). The customer's
     * home cell — for customer rows it's derived from the MSISDN prefix at
     * registration; for system-user rows it's the deployment's
     * {@code INNBUCKS_COUNTRY}. Defaulted to "ZW" in the builder so test
     * fixtures don't break, but production paths always set it explicitly.
     * Part of the composite {@code uk_users_phone_country} constraint.
     */
    @Column(name = "home_country", nullable = false, length = 2)
    @Builder.Default
    private String homeCountry = "ZW";

    @Column(nullable = false)
    private String password;

    /**
     * The account's role NAMES, referencing {@code roles.name} (V35).
     *
     * <p>Held as strings rather than the {@link Role} enum so an operator-created
     * role is assignable without a redeploy — the point of making roles data.
     * The column was already {@code VARCHAR(255)} with no CHECK constraint (V3,
     * and V22 dropped a stray one that staging had grown), so this is a mapping
     * change only: no migration, and existing rows are already valid.
     *
     * <p>There is no database foreign key to {@code roles} here. Adding one would
     * mean a role deleted out from under a user takes the user row with it (or
     * blocks the delete on a table Hibernate manages as a collection). Instead
     * {@code RoleAdminService} refuses to delete a role anyone still holds, and
     * {@code PermissionResolver} degrades an unresolvable name to "grants
     * nothing" and logs it.
     *
     * <p>{@link Role} survives as a constants holder for the nine built-ins so
     * code can keep saying {@code Role.SUPER_ADMIN} instead of a bare literal —
     * see {@link #hasRole(Role)}.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Builder.Default
    private Set<String> roles = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_default_services", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "service", nullable = false)
    @Builder.Default
    private Set<String> defaultServices = new HashSet<>();

    private boolean mfaEnabled = false;

    /**
     * Base32 TOTP shared secret. Stored AES-GCM-encrypted at rest via
     * {@link MfaSecretConverter}; callers see the plaintext.
     */
    @Convert(converter = MfaSecretConverter.class)
    @Column(name = "mfa_secret", columnDefinition = "TEXT")
    private String mfaSecret;

    @Column(nullable = false)
    private boolean active = false;

    // Business-account flag, set at registration. When true the account has a
    // TenantProfile carrying businessName / businessAddress / bpoNumber.
    @Column(name = "is_business", nullable = false)
    private boolean business;

    // Approval gate. Registration creates the account unapproved with an
    // unusable placeholder password; the first SUPER_ADMIN activation approves
    // it and assigns the default password. Guards a later activation toggle
    // from overwriting a password the user has since changed.
    @Column(nullable = false)
    private boolean approved;

    // Forces a password change on next login — set when the default password is
    // assigned at approval, cleared by /auth/change-password.
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    // Set by CredentialDeliveryListener as soon as ANY channel (email / SMS /
    // WhatsApp) confirms delivery of the temp password. NULL means nothing
    // reached the user — UserAdminService.setActive treats a retried activation
    // on that row as a real retry (re-publishes the event with a fresh temp
    // password) instead of an idempotent no-op. UserResponseDTO exposes this
    // so the admin UI can flag "credentials not delivered, click resend".
    @Column(name = "credential_delivered_at")
    private LocalDateTime credentialDeliveredAt;

    // Loyalty scope for shop staff. SHOP_ADMIN and SHOP_USER tokens carry these
    // as JWT claims so loyalty-service can scope shop-level operations without
    // a per-request lookup. Null for non-shop users.
    @Column(name = "loyalty_shop_id")
    private UUID loyaltyShopId;

    @Column(name = "loyalty_merchant_id")
    private UUID loyaltyMerchantId;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    private LocalDateTime updatedAt;

    /** Acting principal's user_uuid (or JWT email fallback) that created this
     *  account — auto-stamped by JPA auditing (see JpaAuditingConfig). Null for
     *  self-registration / system writes with no authenticated principal. */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Acting principal on the last update (e.g. the admin who activated / reset
     *  this account); null when the update came from an unauthenticated flow. */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    // Monotonically increasing per-user counter ("session epoch"). Every
    // access JWT carries the value at mint time; JwtFilter rejects tokens
    // whose claim is stale relative to the DB. /auth/login bumps this in
    // the same transaction that revokes all prior refresh-token families,
    // so a second login on any device immediately invalidates the first
    // device's tokens inside user-service (and within 15 min everywhere
    // else, once the access token's natural TTL elapses).
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0;

    /**
     * Running count of consecutive wrong-password attempts. Bumped on
     * every failed /auth/login against this account; reset to 0 on a
     * successful login or when an expired {@link #lockedUntil} window
     * elapses. Once it reaches the configured threshold
     * ({@code innbucks.account-lockout.max-attempts}) the row is locked
     * by stamping {@link #lockedUntil}.
     */
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    /**
     * Account lockout deadline. {@code null} means not locked; a
     * timestamp in the future means locked until then (returns 423 on
     * every /auth/login). A timestamp in the past means the lockout
     * has elapsed — the next attempt auto-resets both this and
     * {@link #failedLoginAttempts} as part of the same write.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Running count of consecutive wrong TOTP / backup codes at the MFA login
     * step (POST /auth/login/mfa). Kept SEPARATE from
     * {@link #failedLoginAttempts} (OWASP A04/A07, see V31): the password step
     * resets that counter on success, so sharing it would let a
     * password-holding attacker clear their MFA strikes by re-authenticating.
     * Bumped only by the MFA verification path; reset to 0 on a correct code or
     * once an expired {@link #mfaLockedUntil} window elapses.
     */
    @Column(name = "mfa_failed_attempts", nullable = false)
    @Builder.Default
    private int mfaFailedAttempts = 0;

    /**
     * MFA-step lockout deadline. {@code null} means not locked; a timestamp in
     * the future means /auth/login/mfa returns 423 until then (independent of a
     * freshly minted mfaToken). Cleared on a successful code or auto-reset once
     * the window elapses.
     */
    @Column(name = "mfa_locked_until")
    private Instant mfaLockedUntil;

    /**
     * For TEAM_MEMBER rows: the user_uuid of the EVENT_ORGANIZER that
     * created this team member. Null for every other role. Drives the
     * "list my team" query and the "can this scanner work this event"
     * authorization check in booking-service (where the team member's
     * organizerUuid JWT claim must equal the event's tenant_user_uuid).
     *
     * <p>FK to {@link #userUuid} with {@code ON DELETE RESTRICT} as a
     * backstop — real soft-delete is via {@link #active}=false +
     * {@link #tokenVersion}++, the row stays around so the audit trail
     * (booking_items.redeemed_by_user_uuid + redeemed_by_name) never
     * orphans.
     */
    @Column(name = "created_by_organizer_uuid")
    private UUID createdByOrganizerUuid;

    /**
     * Convenience overload for the nine built-in roles, so call sites keep
     * reading {@code hasRole(Role.SUPER_ADMIN)} rather than a bare string
     * literal — a typo in an enum constant fails to compile, a typo in a literal
     * silently never matches.
     */
    public boolean hasRole(Role role) {
        return role != null && hasRole(role.name());
    }

    /** Whether the account holds this role name — built-in or operator-created. */
    public boolean hasRole(String roleName) {
        return roles != null && roleName != null && roles.contains(roleName);
    }

    /**
     * A MUTABLE role-name set from built-in constants, for the {@code .roles(…)}
     * builder calls that seed an account with a known role.
     *
     * <p>Mutable on purpose: the result is handed to Hibernate as the entity's
     * {@code @ElementCollection} instance, and later code (role grants in
     * {@code ServiceRequestService}, {@code UserAdminService.setRoles}) mutates
     * that collection in place rather than replacing the reference. An immutable
     * {@code Set.of(…)} here would compile and then throw at the first grant.
     */
    public static Set<String> roleNames(Role... roles) {
        Set<String> names = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role != null) names.add(role.name());
        }
        return names;
    }

    /**
     * Assigns a fresh {@link #userUuid} when the row is first persisted, so
     * the column is populated whether the caller built the entity through
     * the builder, the no-args constructor, or any other path. The DB has a
     * matching {@code DEFAULT gen_random_uuid()} (V20) as a backstop for
     * direct-SQL inserts (test fixtures, Flyway data migrations), but the
     * application-side assignment lets the calling code read the value back
     * immediately without a refetch.
     */
    @PrePersist
    void assignUserUuidIfMissing() {
        if (userUuid == null) {
            userUuid = UUID.randomUUID();
        }
    }

    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * The nine BUILT-IN roles.
     *
     * <p>Since V35 this is no longer the closed set of roles the platform can
     * have — roles live in the {@code roles} table and an operator creates more
     * through {@code POST /admin/roles}. What this enum still is: the names that
     * <em>code</em> references directly, so they can be written as constants
     * instead of literals and a typo fails the build. Every constant here has a
     * matching {@code builtin = true} row seeded by V35, and
     * {@code RoleAdminService} refuses to delete or rename those rows precisely
     * because this enum (and {@code Services.BUNDLE_ROLES}, and
     * {@code DataInitializer}) still names them.
     *
     * <p>Do NOT add a constant here for a new operator-facing role — create it
     * through the API. Add one only when this service's own code needs to name
     * the role, which in practice means a {@code @PreAuthorize} or a seed does.
     */
    public enum Role {
        SUPER_ADMIN,
        // Internal platform staff. Unlike the roles below, these are NOT scoped
        // to a tenant, merchant, shop or organizer — they are platform-side
        // people, so they carry no scoping claim and nothing derives a service
        // bundle from them (see Services.BUNDLE_ROLES).
        //
        // Both are treated as system users everywhere that distinction is drawn
        // from "not CUSTOMER" — most importantly MfaPolicy, so a holder is
        // subject to the same MFA enrolment/challenge rules as any other staff
        // account, and UserRepository's system-user projection, so they show up
        // in the SUPER_ADMIN user list rather than the customer list.
        //
        // They are assigned via PUT /admin/users/{id}/roles. Neither grants
        // access to any endpoint on its own yet: no @PreAuthorize names them, so
        // a holder authenticates and is authorized for exactly what a
        // role-less account is. Add them to the specific @PreAuthorize lists
        // when their remit is decided, rather than pre-emptively here.
        PRODUCT_OFFICER,
        PRODUCT_MANAGER,
        EVENT_ORGANIZER,
        // Event-organizer team member (gate-staff, scanner operator). Created
        // by an EVENT_ORGANIZER via POST /event-organizer/team-members and
        // stamped with the organizer's user_uuid in
        // {@link User#createdByOrganizerUuid}. Their JWT carries the parent
        // organizer's uuid as the {@code organizerUuid} claim so booking-
        // service can authorize them to scan tickets for any event owned by
        // that organizer without a per-request cross-service lookup.
        TEAM_MEMBER,
        MERCHANT_ADMIN,
        // Shop-level staff. SHOP_ADMINs are created by a MERCHANT_ADMIN and
        // manage staff at a specific shop. SHOP_USERs are created by a
        // SHOP_ADMIN and operate the POS at that shop.
        SHOP_ADMIN,
        SHOP_USER,
        CUSTOMER
    }
}
