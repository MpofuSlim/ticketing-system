package com.innbucks.userservice.controller;

import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console flows that existed before staff accounts (V44) behave exactly as
 * they did for business accounts: the {@code setRoles} scope guards for shop
 * staff and team members, and a first approval still minting a temporary
 * password. (Register without {@code roles} is pinned in
 * {@code RegisterRolesFieldTest}.)
 */
class ExistingConsoleFlowsRegressionTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private ResultActions setRoles(User target, String roles) throws Exception {
        return h.mvc.perform(put("/admin/users/{id}/roles", target.getId()).principal(as(OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":" + roles + "}"));
    }

    @Test
    @DisplayName("SHOP_ADMIN on an account never scoped to a shop: the same 400 as before")
    void shopAdminScope() throws Exception {
        User unscoped = h.account("tendai@shop.co.zw", "CUSTOMER");
        setRoles(unscoped, "[\"SHOP_ADMIN\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("SHOP_ADMIN and SHOP_USER require the account to be scoped "
                        + "to a loyalty merchant and shop; create shop staff via POST /admin/shop-staff/admins "
                        + "or POST /admin/shop-staff/users instead."));

        User scoped = h.account("chipo@shop.co.zw", "SHOP_USER");
        scoped.setLoyaltyMerchantId(UUID.randomUUID());
        scoped.setLoyaltyShopId(UUID.randomUUID());
        setRoles(scoped, "[\"SHOP_ADMIN\"]").andExpect(status().isOk());
        assertThat(scoped.getRoles()).containsExactly("SHOP_ADMIN");
    }

    @Test
    @DisplayName("TEAM_MEMBER on an account with no parent organizer: the same 400 as before")
    void teamMemberScope() throws Exception {
        User orphan = h.account("gate@harare-arena.co.zw", "CUSTOMER");
        setRoles(orphan, "[\"TEAM_MEMBER\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("TEAM_MEMBER requires the account to be stamped with its "
                        + "parent EVENT_ORGANIZER; create team members via POST /event-organizer/team-members "
                        + "instead."));

        User stamped = h.account("scanner@harare-arena.co.zw", "CUSTOMER");
        stamped.setCreatedByOrganizerUuid(UUID.randomUUID());
        setRoles(stamped, "[\"TEAM_MEMBER\"]").andExpect(status().isOk());
    }

    @Test
    @DisplayName("first approval of a business registration still mints and delivers a temporary password")
    void approvalMintsTemporaryPassword() throws Exception {
        User registrant = h.account("rudo@chikwanha-traders.co.zw", "MERCHANT_ADMIN");
        registrant.setActive(false);
        registrant.setApproved(false);
        registrant.setPhoneNumber("+263771234567");
        String before = registrant.getPassword();
        h.mvc.perform(put("/admin/users/{id}/active", registrant.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        assertThat(registrant.isActive()).isTrue();
        assertThat(registrant.isApproved()).isTrue();
        assertThat(registrant.isMustChangePassword()).isTrue();
        assertThat(registrant.getPassword()).isNotEqualTo(before).startsWith("{x}");
        assertThat(h.events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(CredentialDeliveryRequested.class,
                c -> assertThat(c.reason()).isEqualTo(CredentialDeliveryRequested.Reason.APPROVAL)));
    }
}
