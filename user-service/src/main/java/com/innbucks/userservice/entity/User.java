package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
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

    @ElementCollection(targetClass = Role.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_default_services", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "service", nullable = false)
    @Builder.Default
    private Set<String> defaultServices = new HashSet<>();

    private boolean mfaEnabled = false;
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

    // Loyalty scope for shop staff. SHOP_ADMIN and SHOP_USER tokens carry these
    // as JWT claims so loyalty-service can scope shop-level operations without
    // a per-request lookup. Null for non-shop users.
    @Column(name = "loyalty_shop_id")
    private UUID loyaltyShopId;

    @Column(name = "loyalty_merchant_id")
    private UUID loyaltyMerchantId;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

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

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
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

    public enum Role {
        SUPER_ADMIN,
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
