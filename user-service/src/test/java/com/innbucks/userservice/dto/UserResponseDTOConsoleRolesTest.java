package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console does not show CUSTOMER beside an account's other roles — it is
 * the super-app side of the account, not something an admin manages. A
 * customer-only account keeps it, or it would render with no roles at all.
 * A SUPER_ADMIN shows SUPER_ADMIN alone (plus any business role): it holds
 * every permission already.
 */
class UserResponseDTOConsoleRolesTest {

    @Test
    void customerIsHidden_whenTheAccountHoldsAnotherRole() {
        User user = User.builder().roles(User.roleNames(
                User.Role.CUSTOMER, User.Role.EVENT_ORGANIZER, User.Role.MERCHANT_ADMIN))
                .build();

        assertThat(UserResponseDTO.from(user).getRoles())
                .containsExactlyInAnyOrder("EVENT_ORGANIZER", "MERCHANT_ADMIN");
    }

    // --- SUPER_ADMIN: every permission already, so the staff roles beside it add nothing ---

    @Test
    void aSuperAdmin_showsOnlySuperAdmin_whateverStaffRolesItAlsoHolds() {
        // The deputies on staging carried five staff roles each beside SUPER_ADMIN.
        User user = User.builder().roles(new java.util.LinkedHashSet<>(java.util.List.of(
                "CALL_CENTER_AGENT", "CALL_CENTER_SUPERVISOR", "FRAUD_DESK", "PRODUCT_MANAGER",
                "PRODUCT_OFFICER", "SUPER_ADMIN", "DEV_READONLY")))
                .build();

        assertThat(UserResponseDTO.from(user).getRoles()).containsExactly("SUPER_ADMIN");
    }

    @Test
    void aSuperAdmin_stillShowsABusinessRole_afterSuperAdmin_andNeverCustomer() {
        // A business role carries scope claims the wildcard does not, and staff
        // never belong to a business, so one on a SUPER_ADMIN must stay visible.
        User user = User.builder().roles(User.roleNames(
                User.Role.CUSTOMER, User.Role.MERCHANT_ADMIN, User.Role.EVENT_ORGANIZER,
                User.Role.PRODUCT_MANAGER, User.Role.SUPER_ADMIN))
                .build();

        assertThat(UserResponseDTO.from(user).getRoles())
                .containsExactly("SUPER_ADMIN", "EVENT_ORGANIZER", "MERCHANT_ADMIN");
    }

    @Test
    void aSuperAdmin_keepsEveryRole_onTheAccount() {
        User user = User.builder().roles(User.roleNames(User.Role.PRODUCT_MANAGER, User.Role.SUPER_ADMIN)).build();

        UserResponseDTO.from(user);

        assertThat(user.getRoles()).containsExactlyInAnyOrder("PRODUCT_MANAGER", "SUPER_ADMIN");
    }

    @Test
    void customerIsShown_onACustomerOnlyAccount() {
        User user = User.builder().roles(User.roleNames(User.Role.CUSTOMER)).build();

        assertThat(UserResponseDTO.from(user).getRoles()).containsExactly("CUSTOMER");
    }

    @Test
    void rolesWithoutCustomer_areUnchanged() {
        User user = User.builder().roles(User.roleNames(User.Role.SHOP_ADMIN)).build();

        assertThat(UserResponseDTO.from(user).getRoles()).containsExactly("SHOP_ADMIN");
    }

    @Test
    void noRoles_isAnEmptyList() {
        assertThat(UserResponseDTO.consoleRoles(null)).isEmpty();
        assertThat(UserResponseDTO.consoleRoles(java.util.Set.of())).isEmpty();
    }

    @Test
    void theAccountItselfStillHoldsCustomer() {
        User user = User.builder().roles(User.roleNames(User.Role.CUSTOMER, User.Role.MERCHANT_ADMIN)).build();

        UserResponseDTO.from(user);

        assertThat(user.getRoles()).contains("CUSTOMER");
    }
}
