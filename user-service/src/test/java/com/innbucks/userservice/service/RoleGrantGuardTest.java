package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How {@link RoleGrantGuard} resolves the acting administrator — live, and failing closed. */
class RoleGrantGuardTest {

    private UserRepository users;
    private RoleRepository roles;
    private RoleGrantGuard guard;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        BuiltInRoleRows.stub(roles, BuiltInRoleRows.custom("LEGACY", "users:read", "refunds:approve"));
        guard = new RoleGrantGuard(users, roles);
    }

    @Test
    @DisplayName("the platform owner holds the whole concrete catalog and the wildcard")
    void ownerHoldsEverything() {
        BuiltInRoleRows.caller(users, "admin@innbucks.co.zw", "SUPER_ADMIN");
        RoleGrantGuard.Caller caller = guard.resolveCaller("admin@innbucks.co.zw");

        assertThat(caller.wildcard()).isTrue();
        assertThat(caller.permissions()).containsExactlyInAnyOrderElementsOf(PermissionCatalog.concrete());
    }

    @Test
    @DisplayName("a subject without '@' is a phone, as the JWT subject is for an account with no email")
    void phoneSubject() {
        User u = User.builder().id(5L).phoneNumber("+263771234567").active(true)
                .roles(new LinkedHashSet<>(List.of("CALL_CENTER_AGENT"))).build();
        when(users.findByPhoneNumber("+263771234567")).thenReturn(Optional.of(u));

        RoleGrantGuard.Caller caller = guard.resolveCaller("+263771234567");
        assertThat(caller.permissions()).containsExactlyInAnyOrder("device-security:read", "device-security:manage");
        assertThat(caller.holdsRole("CALL_CENTER_AGENT")).isTrue();
        verify(users).findByPhoneNumber("+263771234567");
    }

    @Test
    @DisplayName("no subject, an unknown one, or a deactivated account: holds nothing")
    void failsClosed() {
        assertThat(guard.resolveCaller(null).permissions()).isEmpty();
        assertThat(guard.resolveCaller("  ").permissions()).isEmpty();
        assertThat(guard.resolveCaller("ghost@innbucks.co.zw").permissions()).isEmpty();

        User off = BuiltInRoleRows.caller(users, "gone@innbucks.co.zw", "SUPER_ADMIN");
        off.setActive(false);
        RoleGrantGuard.Caller caller = guard.resolveCaller("gone@innbucks.co.zw");
        assertThat(caller.permissions()).isEmpty();
        assertThat(caller.wildcard()).isFalse();
        assertThat(caller.roles()).isEmpty();
    }

    @Test
    @DisplayName("stale codes a role still holds grant nothing — to the caller or to a target")
    void staleCodesGrantNothing() {
        BuiltInRoleRows.caller(users, "legacy@innbucks.co.zw", "LEGACY");
        assertThat(guard.resolveCaller("legacy@innbucks.co.zw").permissions()).containsExactly("users:read");

        User target = User.builder().roles(new LinkedHashSet<>(List.of("LEGACY", "GHOST"))).build();
        assertThat(guard.resolvedPermissions(target)).containsExactly("users:read");
    }
}
