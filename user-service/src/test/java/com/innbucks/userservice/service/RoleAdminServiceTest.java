package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit cover for role administration (V35). No {@code @SpringBootTest} —
 * these are the guard rails, and every one of them is a plain decision on
 * mocked collaborators.
 */
class RoleAdminServiceTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final RoleAdminService service = new RoleAdminService(roles, audit);

    private static Role builtin(String name, String... permissions) {
        return Role.builder().name(name).description(name).builtin(true)
                .permissions(new java.util.LinkedHashSet<>(Set.of(permissions))).build();
    }

    private static Role custom(String name, String... permissions) {
        return Role.builder().name(name).description(name).builtin(false)
                .permissions(new java.util.LinkedHashSet<>(Set.of(permissions))).build();
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("creates a role from existing permissions and normalizes the name")
    void create_happyPath() {
        when(roles.existsById("REFUND_OFFICER")).thenReturn(false);
        when(roles.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role created = service.create("  refund_officer ", "Handles refunds",
                List.of("USERS:READ", " users:password:reset "),
                "admin@innbucks.co.zw", AuditContext.none());

        assertThat(created.getName()).isEqualTo("REFUND_OFFICER");
        assertThat(created.isBuiltin()).isFalse();
        assertThat(created.getCreatedBy()).isEqualTo("admin@innbucks.co.zw");
        // Permission codes are lower-cased; the name is upper-cased. The two
        // namespaces are cased differently on purpose so they can never collide
        // as Spring Security authorities.
        assertThat(created.getPermissions())
                .containsExactlyInAnyOrder("users:read", "users:password:reset");
    }

    @Test
    @DisplayName("refuses a permission that is not in the code catalog")
    void create_unknownPermission_refused() {
        assertThatThrownBy(() -> service.create("REFUND_OFFICER", "d",
                List.of("users:read", "refunds:approve"), "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown permission(s): refunds:approve")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(roles, never()).save(any());
    }

    @Test
    @DisplayName("refuses the '*' wildcard — it would mint a second SUPER_ADMIN")
    void create_wildcard_refused() {
        // The escalation this closes: PUT /admin/users/{id}/roles refuses to
        // GRANT SUPER_ADMIN, so allowing '*' here would reopen exactly that hole
        // through a different door — create a role holding '*', assign it.
        assertThatThrownBy(() -> service.create("SNEAKY_ADMIN", "d",
                List.of(PermissionCatalog.WILDCARD), "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(roles, never()).save(any());
    }

    @Test
    @DisplayName("refuses a name that would not survive the ROLE_ authority round-trip")
    void create_badName_refused() {
        for (String bad : List.of("refund officer", "Refund-Officer", "9LIVES", "X", "")) {
            assertThatThrownBy(() -> service.create(bad, "d", List.of("users:read"),
                    "admin@x.co", AuditContext.none()))
                    .as("name %s", bad)
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("refuses an empty permission set — the role would authorize nothing")
    void create_noPermissions_refused() {
        assertThatThrownBy(() -> service.create("EMPTY_ROLE", "d", List.of(),
                "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one permission");
    }

    @Test
    @DisplayName("refuses a duplicate name with 409")
    void create_duplicate_refused() {
        when(roles.existsById("REFUND_OFFICER")).thenReturn(true);

        assertThatThrownBy(() -> service.create("refund_officer", "d", List.of("users:read"),
                "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------- setPermissions

    @Test
    @DisplayName("replaces a built-in role's permissions — only its NAME is depended on by code")
    void setPermissions_allowedOnBuiltin() {
        when(roles.findById("MERCHANT_ADMIN"))
                .thenReturn(Optional.of(builtin("MERCHANT_ADMIN", "shop-admins:write")));
        when(roles.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role updated = service.setPermissions("MERCHANT_ADMIN",
                List.of("shop-admins:write", "shop-staff:read"), "admin@x.co", AuditContext.none());

        assertThat(updated.getPermissions())
                .containsExactlyInAnyOrder("shop-admins:write", "shop-staff:read");
    }

    @Test
    @DisplayName("refuses to narrow SUPER_ADMIN off the wildcard")
    void setPermissions_cannotNarrowSuperAdmin() {
        // Without this guard an admin could remove roles:write from SUPER_ADMIN
        // and lock every human out of role administration, recoverable only by
        // editing the database directly.
        when(roles.findById("SUPER_ADMIN"))
                .thenReturn(Optional.of(builtin("SUPER_ADMIN", PermissionCatalog.WILDCARD)));

        assertThatThrownBy(() -> service.setPermissions("SUPER_ADMIN", List.of("users:read"),
                "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must keep the '*' permission")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("re-submitting the same permissions is a no-op, and is not audited")
    void setPermissions_noOp() {
        Role role = custom("REFUND_OFFICER", "users:read");
        when(roles.findById("REFUND_OFFICER")).thenReturn(Optional.of(role));

        service.setPermissions("REFUND_OFFICER", List.of("users:read"), "admin@x.co", AuditContext.none());

        verify(roles, never()).save(any());
        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    // --------------------------------------------------------------- delete

    @Test
    @DisplayName("refuses to delete a built-in role — code names it, so the break would be silent")
    void delete_builtin_refused() {
        when(roles.findById("MERCHANT_ADMIN"))
                .thenReturn(Optional.of(builtin("MERCHANT_ADMIN", "shop-admins:write")));

        assertThatThrownBy(() -> service.delete("MERCHANT_ADMIN", "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(roles, never()).delete(any());
    }

    @Test
    @DisplayName("refuses to delete a role accounts still hold — holders would silently lose access")
    void delete_stillHeld_refused() {
        when(roles.findById("REFUND_OFFICER")).thenReturn(Optional.of(custom("REFUND_OFFICER", "users:read")));
        when(roles.countUsersHolding("REFUND_OFFICER")).thenReturn(3L);

        assertThatThrownBy(() -> service.delete("REFUND_OFFICER", "admin@x.co", AuditContext.none()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("still assigned to 3 account(s)")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(roles, never()).delete(any());
    }

    @Test
    @DisplayName("deletes an unheld custom role")
    void delete_happyPath() {
        Role role = custom("REFUND_OFFICER", "users:read");
        when(roles.findById("REFUND_OFFICER")).thenReturn(Optional.of(role));
        when(roles.countUsersHolding("REFUND_OFFICER")).thenReturn(0L);

        service.delete("REFUND_OFFICER", "admin@x.co", AuditContext.none());

        verify(roles).delete(role);
    }

    @Test
    @DisplayName("a missing role is a 404, not a null")
    void get_missing() {
        when(roles.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class);
    }
}
