package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlatformOwnerGuard}: the platform owner is the caller's LIVE row —
 * the {@code platform_owner} flag (V52), an active account and the SUPER_ADMIN
 * role, all three — never a token claim and never the bootstrap address. Every
 * other caller, a deputy SUPER_ADMIN above all, is {@code 403 platform_owner_only},
 * and the refusal is recorded.
 */
class PlatformOwnerGuardTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final PlatformOwnerGuard guard;

    PlatformOwnerGuardTest() {
        RoleGrantGuard roleGrantGuard = new RoleGrantGuard(users, mock(RoleRepository.class));
        roleGrantGuard.setAuditService(audit);
        guard = new PlatformOwnerGuard(users, roleGrantGuard);
    }

    private User account(String email, boolean owner, boolean active, String... roles) {
        User user = User.builder().id((long) Math.abs(email.hashCode())).userUuid(UUID.randomUUID()).email(email)
                .roles(new LinkedHashSet<>(List.of(roles))).active(active).approved(true).platformOwner(owner)
                .build();
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void theSeededOwnerPasses() {
        User owner = account("admin@innbucks.co.zw", true, true, "SUPER_ADMIN");
        assertThat(guard.requireOwner("admin@innbucks.co.zw", "super_admin_promote")).isSameAs(owner);
        assertThat(guard.isOwner("admin@innbucks.co.zw")).isTrue();
    }

    @Test
    void aDeputySuperAdminIsRefused_andTheAttemptIsRecorded() {
        account("rudo.deputy@innbucks.co.zw", false, true, "SUPER_ADMIN");

        assertThatThrownBy(() -> guard.requireOwner("rudo.deputy@innbucks.co.zw", "super_admin_promote"))
                .isInstanceOfSatisfying(StaffPolicyException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getErrorCode()).isEqualTo("platform_owner_only");
                    assertThat(ex.getMessage()).isEqualTo(StaffPolicyException.PLATFORM_OWNER_ONLY_MESSAGE);
                });
        assertThat(guard.isOwner("rudo.deputy@innbucks.co.zw")).isFalse();
        verify(audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq("rudo.deputy@innbucks.co.zw"),
                anyString(), any(), any(), eq("platform_owner_only"), anyMap(), any());
    }

    @Test
    void aDeactivatedOwnerRowIsRefused() {
        account("admin@innbucks.co.zw", true, false, "SUPER_ADMIN");
        assertThat(guard.isOwner("admin@innbucks.co.zw")).isFalse();
        assertThatThrownBy(() -> guard.requireOwner("admin@innbucks.co.zw", "super_admin_promote"))
                .isInstanceOf(StaffPolicyException.class);
    }

    @Test
    void anOwnerRowThatNoLongerHoldsSuperAdminIsRefused() {
        // Unreachable through the API (nothing can demote the owner), but the
        // check fails closed on a row edited by hand.
        account("admin@innbucks.co.zw", true, true, "CALL_CENTER_AGENT");
        assertThat(guard.isOwner("admin@innbucks.co.zw")).isFalse();
    }

    @Test
    void theBootstrapAddressAloneIsNotOwnership() {
        // A SUPER_ADMIN sitting at the configured bootstrap address but without
        // the flag (e.g. after an address rotation) is a deputy, not the owner.
        account("admin@innbucks.co.zw", false, true, "SUPER_ADMIN");
        assertThat(guard.isOwner("admin@innbucks.co.zw")).isFalse();
    }

    @Test
    void anUnknownOrBlankCallerIsRefused() {
        assertThat(guard.isOwner("nobody@innbucks.co.zw")).isFalse();
        assertThat(guard.isOwner(null)).isFalse();
        assertThat(guard.isOwner(" ")).isFalse();
        assertThatThrownBy(() -> guard.requireOwner(null, "super_admin_demote"))
                .isInstanceOf(StaffPolicyException.class);
    }

    @Test
    void aPhoneSubjectResolvesByPhone() {
        User owner = User.builder().id(5L).phoneNumber("+263771000005").roles(new LinkedHashSet<>(List.of("SUPER_ADMIN")))
                .active(true).platformOwner(true).build();
        when(users.findByPhoneNumber("+263771000005")).thenReturn(Optional.of(owner));
        assertThat(guard.isOwner("+263771000005")).isTrue();
    }
}
