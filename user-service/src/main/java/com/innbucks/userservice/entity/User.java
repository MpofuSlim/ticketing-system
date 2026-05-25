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

    @Column(nullable = false)
    private String firstName;

    private String middleName;

    @Column(nullable = false)
    private String lastName;

    @Column(unique = true, nullable = false)
    private String phoneNumber;

    @Column(unique = true)
    private String email;

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

    public boolean hasRole(Role role) {
        return roles != null && roles.contains(role);
    }

    public enum Role {
        SUPER_ADMIN,
        EVENT_ORGANIZER,
        MERCHANT_ADMIN,
        // Shop-level staff. SHOP_ADMINs are created by a MERCHANT_ADMIN and
        // manage staff at a specific shop. SHOP_USERs are created by a
        // SHOP_ADMIN and operate the POS at that shop.
        SHOP_ADMIN,
        SHOP_USER,
        CUSTOMER
    }
}
