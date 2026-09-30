package com.innbucks.userservice.security;

import com.innbucks.userservice.security.PermissionCatalog.Entry;
import com.innbucks.userservice.security.PermissionCatalog.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every permission declares who its authority reaches, and a code nobody
 * declared is treated as the broader of the two.
 *
 * <p>The scope is what the staff rules read: holding a PLATFORM code makes a
 * role a staff role, and removing one from a role signs its holders out at
 * once. A code that slipped in without a decision, or a stale grant left behind
 * by a removed code, must never be what lets a role out of those rules.
 */
class PermissionCatalogScopeTest {

    /** The split as the design fixes it. A new code must be added here deliberately. */
    private static final Set<String> TENANT = Set.of(
            "team-members:read", "team-members:write", "team-members:manage",
            "shop-admins:write", "shop-users:write", "shop-staff:read",
            "shop-staff:merchant:read", "shop-staff:password:reset");

    @Test
    @DisplayName("every catalog entry carries a scope")
    void everyEntryHasAScope() {
        assertThat(PermissionCatalog.ENTRIES).isNotEmpty();
        for (Entry e : PermissionCatalog.ENTRIES.values()) {
            assertThat(e.scope()).as(e.code()).isNotNull();
            assertThat(PermissionCatalog.scopeOf(e.code())).as(e.code()).isEqualTo(e.scope());
        }
        // ALL (the operator-facing description map) is the same set of codes.
        assertThat(PermissionCatalog.ALL.keySet()).isEqualTo(PermissionCatalog.ENTRIES.keySet());
    }

    @Test
    @DisplayName("a scope is a required argument — an entry without one cannot be built")
    void scopeIsRequired() {
        assertThatThrownBy(() -> new Entry("refunds:approve", "Approve refunds", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope is required");
    }

    @Test
    @DisplayName("the TENANT codes are exactly the business-scoped ones; everything else is PLATFORM")
    void theSplitIsPinned() {
        Set<String> tenant = PermissionCatalog.ENTRIES.values().stream()
                .filter(e -> e.scope() == Scope.TENANT).map(Entry::code)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(tenant).isEqualTo(new TreeSet<>(TENANT));

        for (String code : Set.of("users:read", "users:merchants:read", "users:activation:write",
                "users:roles:write", "users:mfa:reset", "users:password:reset", "roles:read", "roles:write",
                "service-requests:read", "service-requests:approve", "organizations:read",
                "device-security:read", "device-security:manage", "device-security:fraud",
                "marketplace-support:read", "marketplace-support:manage", "marketplace-support:supervise",
                "loyalty-support:read", "loyalty-support:manage", "loyalty-support:supervise",
                "customer-messages:send")) {
            assertThat(PermissionCatalog.scopeOf(code)).as(code).isEqualTo(Scope.PLATFORM);
        }
        assertThat(PermissionCatalog.scopeOf(PermissionCatalog.WILDCARD)).isEqualTo(Scope.PLATFORM);
    }

    @Test
    @DisplayName("an unknown code — a stale grant, or anything unexpected — classifies as PLATFORM")
    void unknownCodeFailsClosed() {
        assertThat(PermissionCatalog.scopeOf("refunds:approve")).isEqualTo(Scope.PLATFORM);
        assertThat(PermissionCatalog.scopeOf("")).isEqualTo(Scope.PLATFORM);
        assertThat(PermissionCatalog.scopeOf(null)).isEqualTo(Scope.PLATFORM);
    }

    @Test
    @DisplayName("the codes reserved to the wildcard are the ones that hand out authority")
    void reservedToTheWildcard() {
        assertThat(PermissionCatalog.WILDCARD_RESERVED).containsExactlyInAnyOrder(
                "roles:write", "users:roles:write",
                "staff:read", "staff:create", "staff:manage", "organizations:manage");
        // Every reserved code the catalog already defines is PLATFORM — a TENANT
        // code handing out authority would be a contradiction.
        for (String code : PermissionCatalog.WILDCARD_RESERVED) {
            assertThat(PermissionCatalog.scopeOf(code)).as(code).isEqualTo(Scope.PLATFORM);
        }
        assertThat(PermissionCatalog.isReservedToWildcard("roles:read")).isFalse();
        assertThat(PermissionCatalog.isReservedToWildcard(null)).isFalse();
    }
}
