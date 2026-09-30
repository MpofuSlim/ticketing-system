package com.innbucks.userservice.dto;

import com.innbucks.userservice.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console does not show CUSTOMER beside an account's other roles — it is
 * the super-app side of the account, not something an admin manages. A
 * customer-only account keeps it, or it would render with no roles at all.
 */
class UserResponseDTOConsoleRolesTest {

    @Test
    void customerIsHidden_whenTheAccountHoldsAnotherRole() {
        User user = User.builder().roles(User.roleNames(
                User.Role.CUSTOMER, User.Role.EVENT_ORGANIZER, User.Role.MERCHANT_ADMIN, User.Role.SUPER_ADMIN))
                .build();

        assertThat(UserResponseDTO.from(user).getRoles())
                .containsExactlyInAnyOrder("EVENT_ORGANIZER", "MERCHANT_ADMIN", "SUPER_ADMIN");
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
