package com.innbucks.userservice.testsupport;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The built-in role rows exactly as the migrations seed them (V35's grants plus
 * V43's call-center roles and V45's support grants), for unit tests that mock {@link RoleRepository}.
 * {@code BuiltInRoleSeedTest}'s Postgres half migrates a fresh database and
 * asserts its built-in {@code role_permissions} EQUAL {@link #GRANTS}, so this
 * copy cannot drift from the migrations unnoticed.
 */
public final class BuiltInRoleRows {

    public static final Map<String, Set<String>> GRANTS;

    static {
        Map<String, Set<String>> g = new LinkedHashMap<>();
        g.put("SUPER_ADMIN", Set.of("*"));
        g.put("PRODUCT_OFFICER", Set.of("users:merchants:read"));
        g.put("PRODUCT_MANAGER", Set.of("users:merchants:read"));
        g.put("EVENT_ORGANIZER", Set.of("team-members:read", "team-members:write", "team-members:manage"));
        g.put("TEAM_MEMBER", Set.of());
        g.put("MERCHANT_ADMIN", Set.of("shop-admins:write", "shop-staff:read", "shop-staff:merchant:read",
                "shop-staff:password:reset"));
        g.put("SHOP_ADMIN", Set.of("shop-users:write", "shop-staff:read", "shop-staff:password:reset"));
        g.put("SHOP_USER", Set.of());
        g.put("CUSTOMER", Set.of());
        g.put("CALL_CENTER_AGENT", Set.of("device-security:read", "device-security:manage",
                "marketplace-support:read", "marketplace-support:manage",
                "loyalty-support:read", "loyalty-support:manage", "customer-messages:send"));
        g.put("CALL_CENTER_SUPERVISOR", Set.of("device-security:read", "device-security:manage",
                "marketplace-support:read", "marketplace-support:manage", "marketplace-support:supervise",
                "loyalty-support:read", "loyalty-support:manage", "loyalty-support:supervise",
                "customer-messages:send"));
        g.put("FRAUD_DESK", Set.of("device-security:read", "device-security:fraud"));
        GRANTS = java.util.Collections.unmodifiableMap(g);
    }

    private BuiltInRoleRows() {}

    public static Role builtin(String name) {
        Set<String> grants = GRANTS.get(name);
        if (grants == null) throw new IllegalArgumentException("not a built-in: " + name);
        return Role.builder().name(name).description(name).builtin(true)
                .permissions(new LinkedHashSet<>(grants)).build();
    }

    public static Role custom(String name, String... permissions) {
        return Role.builder().name(name).description(name).builtin(false)
                .permissions(new LinkedHashSet<>(List.of(permissions))).build();
    }

    /**
     * Stubs {@code findAllByNameIn} and {@code findById} to answer from the
     * built-ins plus {@code extra}. An unknown name simply does not resolve, as
     * in the database.
     */
    public static void stub(RoleRepository roles, Role... extra) {
        Map<String, Role> rows = new LinkedHashMap<>();
        GRANTS.keySet().forEach(n -> rows.put(n, builtin(n)));
        for (Role r : extra) rows.put(r.getName(), r);
        when(roles.findAllByNameIn(any())).thenAnswer(inv -> {
            Collection<String> names = inv.getArgument(0);
            if (names == null) return List.of();
            return names.stream().filter(rows::containsKey).map(rows::get).toList();
        });
        when(roles.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get((String) inv.getArgument(0))));
    }

    /** An active account holding {@code roles}, resolvable by {@code email} as a caller. */
    public static User caller(UserRepository users, String email, String... roles) {
        User user = User.builder().id((long) Math.abs(email.hashCode()) + 10_000L).userUuid(UUID.randomUUID())
                .email(email).roles(new LinkedHashSet<>(List.of(roles))).active(true).approved(true).build();
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        return user;
    }
}
