package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How {@link RoleGrantGuard} resolves the acting administrator — live, and failing
 * closed — and how it compares that authority with what is handed out or acted on.
 */
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
        assertThat(caller.permissions())
                .containsExactlyInAnyOrderElementsOf(BuiltInRoleRows.GRANTS.get("CALL_CENTER_AGENT"));
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
    @DisplayName("stale codes grant the CALLER nothing, but count against it on a role or a target")
    void staleCodesGrantNothing_butAreNeverCovered() {
        BuiltInRoleRows.caller(users, "legacy@innbucks.co.zw", "LEGACY");
        RoleGrantGuard.Caller legacy = guard.resolveCaller("legacy@innbucks.co.zw");
        assertThat(legacy.permissions()).containsExactly("users:read");

        // Read as STORED on the other side: the stale code stays in, and only the
        // wildcard covers it.
        User target = User.builder().id(9L).roles(new LinkedHashSet<>(List.of("LEGACY", "GHOST"))).build();
        assertThat(guard.storedGrants(target)).containsExactlyInAnyOrder("users:read", "refunds:approve");
        assertThat(legacy.holdsAll(guard.storedGrants(target))).isFalse();

        BuiltInRoleRows.caller(users, "admin@innbucks.co.zw", "SUPER_ADMIN");
        RoleGrantGuard.Caller owner = guard.resolveCaller("admin@innbucks.co.zw");
        assertThat(owner.holdsAll(guard.storedGrants(target))).isTrue();
        assertThat(owner.holdsAll(Set.of("*"))).isTrue();
        assertThat(legacy.holdsAll(Set.of("*"))).isFalse();
    }

    @Test
    @DisplayName("an unresolved caller is refused outright — even a role or target carrying no permission")
    void anUnresolvedCallerIsRefusedEverything() {
        RoleGrantGuard.Caller nobody = guard.resolveCaller("ghost@innbucks.co.zw");
        assertThat(nobody.resolved()).isFalse();
        assertThat(nobody.holdsAll(Set.of())).isFalse();

        // CUSTOMER grants nothing, so "holds everything it grants" is vacuously
        // true — the resolved flag is what refuses it.
        assertThatThrownBy(() -> guard.requireMayAssign(nobody, List.of(BuiltInRoleRows.builtin("CUSTOMER"))))
                .isInstanceOfSatisfying(StaffPolicyException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("role_not_assignable");
                    assertThat(e.getExtra()).containsEntry("roles", Map.of("CUSTOMER", "exceeds_your_authority"));
                });

        User customer = User.builder().id(8L).roles(new LinkedHashSet<>(List.of("CUSTOMER"))).build();
        assertThatThrownBy(() -> guard.requireMayManage(nobody, customer))
                .isInstanceOfSatisfying(StaffPolicyException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("target_not_manageable"));
    }

    @Test
    @DisplayName("managing a NAMED-role holder needs that role or the wildcard, on top of the codes")
    void managingANamedRoleHolderNeedsTheName() {
        // Covers users:merchants:read — all PRODUCT_MANAGER resolves to here.
        BuiltInRoleRows.stub(roles, BuiltInRoleRows.custom("MERCHANT_READER", "users:merchants:read"));
        BuiltInRoleRows.caller(users, "reader@innbucks.co.zw", "MERCHANT_READER");
        BuiltInRoleRows.caller(users, "pm.peer@innbucks.co.zw", "PRODUCT_MANAGER");
        User pm = User.builder().id(7L).roles(new LinkedHashSet<>(List.of("PRODUCT_MANAGER"))).build();

        assertThatThrownBy(() -> guard.requireMayManage(guard.resolveCaller("reader@innbucks.co.zw"), pm))
                .isInstanceOfSatisfying(StaffPolicyException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("target_not_manageable");
                    assertThat(e.getExtra()).containsEntry("reason", "exceeds_your_authority");
                });
        assertThatCode(() -> guard.requireMayManage(guard.resolveCaller("pm.peer@innbucks.co.zw"), pm))
                .doesNotThrowAnyException();
    }
}
