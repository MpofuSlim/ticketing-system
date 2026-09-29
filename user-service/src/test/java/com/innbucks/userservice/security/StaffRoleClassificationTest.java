package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.testsupport.BuiltInRoleRows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which roles are STAFF roles: a NAMED one, one holding the wildcard, or one
 * holding any PLATFORM permission. Everything that treats staff differently
 * (phone reset, deactivation authority, named-role assignment) reads this.
 */
class StaffRoleClassificationTest {

    @Test
    @DisplayName("the V35 business built-ins are not staff roles")
    void tenantBuiltInsAreNotStaff() {
        for (String name : List.of("EVENT_ORGANIZER", "MERCHANT_ADMIN", "SHOP_ADMIN", "SHOP_USER",
                "TEAM_MEMBER", "CUSTOMER")) {
            assertThat(StaffRoles.isStaffRole(BuiltInRoleRows.builtin(name))).as(name).isFalse();
        }
    }

    @Test
    @DisplayName("the NAMED roles are staff roles, the three V43 ones included")
    void namedRolesAreStaff() {
        assertThat(StaffRoles.NAMED).containsExactlyInAnyOrder("SUPER_ADMIN", "PRODUCT_OFFICER",
                "PRODUCT_MANAGER", "CALL_CENTER_AGENT", "CALL_CENTER_SUPERVISOR", "FRAUD_DESK");
        for (String name : StaffRoles.NAMED) {
            assertThat(StaffRoles.isStaffRole(BuiltInRoleRows.builtin(name))).as(name).isTrue();
        }
    }

    @Test
    @DisplayName("a custom role holding a PLATFORM permission is staff; one holding only TENANT codes is not")
    void customRolesAreStaffByWhatTheyCanDo() {
        assertThat(StaffRoles.isStaffRole(BuiltInRoleRows.custom("ACCOUNT_AUDITOR", "users:read"))).isTrue();
        assertThat(StaffRoles.isStaffRole(BuiltInRoleRows.custom("SHOP_VIEWER", "shop-staff:read"))).isFalse();
        assertThat(StaffRoles.isStaffRole(
                BuiltInRoleRows.custom("MIXED", "shop-staff:read", "device-security:read"))).isTrue();
    }

    @Test
    @DisplayName("a NAMED role emptied of its PLATFORM codes is still staff — other services act on the name")
    void namedRoleEmptiedIsStillStaff() {
        assertThat(StaffRoles.isStaffRole("PRODUCT_MANAGER", Set.of())).isTrue();
        assertThat(StaffRoles.isStaffRole("PRODUCT_MANAGER", Set.of("shop-staff:read"))).isTrue();
    }

    @Test
    @DisplayName("the wildcard, and a stale code the catalog no longer defines, both make a role staff")
    void wildcardAndUnknownCodesFailClosed() {
        assertThat(StaffRoles.isStaffRole("LEGACY_OWNER", Set.of("*"))).isTrue();
        assertThat(StaffRoles.isStaffRole("LEGACY_REFUNDS", Set.of("refunds:approve"))).isTrue();
        assertThat(StaffRoles.isStaffRole("NOTHING", null)).isFalse();
    }

    @Test
    @DisplayName("an account is staff when ANY of its roles is — including a NAMED name with no roles row")
    void accountClassification() {
        UserRepository users = mock(UserRepository.class);
        RoleRepository roles = mock(RoleRepository.class);
        BuiltInRoleRows.stub(roles, BuiltInRoleRows.custom("ACCOUNT_AUDITOR", "users:read"));
        RoleGrantGuard guard = new RoleGrantGuard(users, roles);

        assertThat(guard.holdsStaffRole(account("CUSTOMER", "MERCHANT_ADMIN"))).isFalse();
        assertThat(guard.holdsStaffRole(account("CUSTOMER", "ACCOUNT_AUDITOR"))).isTrue();
        assertThat(guard.holdsStaffRole(account("EVENT_ORGANIZER", "CALL_CENTER_AGENT"))).isTrue();
        // An orphan user_roles string with no roles row grants nothing, so it is
        // not staff by permission — but a NAMED name is staff by name alone.
        assertThat(guard.holdsStaffRole(account("GHOST_ROLE"))).isFalse();
        assertThat(guard.holdsStaffRole(account("PRODUCT_OFFICER"))).isTrue();
        assertThat(guard.holdsStaffRole(account())).isFalse();
    }

    private static User account(String... roles) {
        return User.builder().id(1L).email("x@innbucks.co.zw").active(true)
                .roles(new LinkedHashSet<>(List.of(roles))).build();
    }
}
