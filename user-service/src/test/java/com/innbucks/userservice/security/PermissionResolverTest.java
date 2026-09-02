package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.repository.RoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Role -> permission expansion, the step that decides what a token can do. */
class PermissionResolverTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionResolver resolver = new PermissionResolver(roles);

    private static Role role(String name, String... permissions) {
        return Role.builder().name(name).description(name)
                .permissions(new LinkedHashSet<>(Set.of(permissions))).build();
    }

    @Test
    @DisplayName("unions the permissions across every role the account holds")
    void unionsAcrossRoles() {
        when(roles.findAllByNameIn(any())).thenReturn(List.of(
                role("A", "users:read"),
                role("B", "users:read", "roles:read")));

        assertThat(resolver.resolve(List.of("A", "B")))
                .containsExactlyInAnyOrder("users:read", "roles:read");
    }

    @Test
    @DisplayName("'*' expands to the whole catalog, so SUPER_ADMIN picks up future permissions")
    void wildcardExpandsToWholeCatalog() {
        // This is the property that stops the platform owner from silently
        // losing access to every endpoint added after the seed: the wildcard is
        // resolved against the LIVE catalog, not a list frozen in a migration.
        when(roles.findAllByNameIn(any()))
                .thenReturn(List.of(role("SUPER_ADMIN", PermissionCatalog.WILDCARD)));

        Set<String> resolved = resolver.resolve(List.of("SUPER_ADMIN"));

        assertThat(resolved).isEqualTo(PermissionCatalog.concrete());
        assertThat(resolved).contains(PermissionCatalog.ROLES_WRITE, PermissionCatalog.USERS_READ);
    }

    @Test
    @DisplayName("the wildcard itself is never emitted into the token")
    void wildcardIsNotItselfEmitted() {
        // The token lists concrete codes only, so hasAuthority needs no
        // wildcard-aware matching and a decoded token reads as exactly what it
        // can do.
        when(roles.findAllByNameIn(any()))
                .thenReturn(List.of(role("SUPER_ADMIN", PermissionCatalog.WILDCARD)));

        assertThat(resolver.resolve(List.of("SUPER_ADMIN")))
                .doesNotContain(PermissionCatalog.WILDCARD);
    }

    @Test
    @DisplayName("a role name that resolves to nothing grants nothing, and does not fail the login")
    void unresolvableRoleNameGrantsNothing() {
        // A role deleted out from under a user row. Degrading to "grants
        // nothing" is the safe direction; failing the login outright would take
        // the account down over a data-drift problem.
        when(roles.findAllByNameIn(any())).thenReturn(List.of(role("A", "users:read")));

        assertThat(resolver.resolve(List.of("A", "GHOST_ROLE"))).containsExactly("users:read");
    }

    @Test
    @DisplayName("a granted permission no longer in the code catalog is dropped, not emitted")
    void staleGrantIsDropped() {
        // Emitting a code nothing enforces is harmless right up until someone
        // reuses the name for something else, at which point the stale grant
        // starts authorizing.
        when(roles.findAllByNameIn(any()))
                .thenReturn(List.of(role("A", "users:read", "retired:permission")));

        assertThat(resolver.resolve(List.of("A"))).containsExactly("users:read");
    }

    @Test
    @DisplayName("no roles means no permissions, without touching the repository")
    void noRoles() {
        assertThat(resolver.resolve(List.of())).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("the catalog's concrete set excludes the wildcard but keeps everything else")
    void catalogShape() {
        assertThat(PermissionCatalog.ALL).containsKey(PermissionCatalog.WILDCARD);
        assertThat(PermissionCatalog.concrete()).doesNotContain(PermissionCatalog.WILDCARD);
        assertThat(PermissionCatalog.concrete()).hasSize(PermissionCatalog.ALL.size() - 1);
    }

    @Test
    @DisplayName("every permission code is lowercase and colon-namespaced, so it can never collide with a ROLE_ authority")
    void permissionCodesCannotCollideWithRoleNames() {
        // Roles become authorities as ROLE_<UPPER_SNAKE>; permissions are
        // granted bare. If a permission code were ever UPPER_SNAKE, a role and a
        // permission could name the same authority and one would silently grant
        // the other.
        for (String code : PermissionCatalog.concrete()) {
            assertThat(code).as("permission %s", code)
                    .isEqualTo(code.toLowerCase(java.util.Locale.ROOT))
                    .contains(":");
        }
    }
}
