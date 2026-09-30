package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/roles} (V44): a staff role may only be ADDED to a
 * staff-eligible account, a profiled account holds staff roles only, removal is
 * never refused on these grounds, and the role rows are locked before any read.
 */
class StaffRoleGrantGuardTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private ResultActions setRoles(User target, String roles) throws Exception {
        return h.mvc.perform(put("/admin/users/{id}/roles", target.getId()).principal(as(OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":" + roles + "}"));
    }

    @Test
    @DisplayName("a staff role for an off-domain account: 400 email_domain_not_allowed")
    void offDomain() throws Exception {
        User merchant = h.account("rudo@shop.co.zw", "MERCHANT_ADMIN");
        setRoles(merchant, "[\"MERCHANT_ADMIN\",\"CALL_CENTER_AGENT\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("email_domain_not_allowed"));
        assertThat(merchant.getRoles()).containsExactly("MERCHANT_ADMIN");
    }

    @Test
    @DisplayName("a staff role for an on-domain account never invited: 400 staff_email_unverified (adopt first)")
    void unverified() throws Exception {
        User legacy = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        setRoles(legacy, "[\"PRODUCT_OFFICER\",\"CALL_CENTER_AGENT\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This account's email has never been confirmed. Send them a staff invite first."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_email_unverified"));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(OWNER), any(),
                eq(String.valueOf(legacy.getId())), eq("USER"), eq("staff_unverified"), any(), any());
    }

    @Test
    @DisplayName("an INVITED (not yet accepted) account is not eligible either")
    void invitedNotEligible() throws Exception {
        User invited = h.account("tariro@innbucks.co.zw", "CALL_CENTER_AGENT");
        invited.setEmailVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        h.profileRows.put(invited.getId(), StaffProfile.builder().userId(invited.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        setRoles(invited, "[\"CALL_CENTER_AGENT\",\"FRAUD_DESK\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("staff_email_unverified"));
    }

    @Test
    @DisplayName("an eligible staff account may be given a staff role")
    void eligible() throws Exception {
        User agent = h.eligibleStaff("tariro@innbucks.co.zw", "CALL_CENTER_AGENT");
        setRoles(agent, "[\"CALL_CENTER_AGENT\",\"FRAUD_DESK\"]").andExpect(status().isOk());
        assertThat(agent.getRoles()).containsExactlyInAnyOrder("CALL_CENTER_AGENT", "FRAUD_DESK");
    }

    @Test
    @DisplayName("a profiled account holds staff roles only: 400 role_not_assignable (not_a_staff_role)")
    void profileInvariant() throws Exception {
        User agent = h.eligibleStaff("tariro@innbucks.co.zw", "CALL_CENTER_AGENT");
        setRoles(agent, "[\"CALL_CENTER_AGENT\",\"MERCHANT_ADMIN\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "These roles can't be given to this account: MERCHANT_ADMIN (not a staff role)."))
                .andExpect(jsonPath("$.data.roles.MERCHANT_ADMIN").value("not_a_staff_role"));
    }

    @Test
    @DisplayName("removing a staff role from an ineligible holder is never refused on these grounds")
    void removalAllowed() throws Exception {
        User legacy = h.account("pm@gmail.com", "PRODUCT_MANAGER", "CUSTOMER");
        setRoles(legacy, "[\"CUSTOMER\"]").andExpect(status().isOk());
        assertThat(legacy.getRoles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("business roles for business accounts are untouched by the staff rules")
    void businessUnaffected() throws Exception {
        User customer = h.account("rudo@example.com", "CUSTOMER");
        setRoles(customer, "[\"CUSTOMER\",\"EVENT_ORGANIZER\"]").andExpect(status().isOk());
    }

    @Test
    @DisplayName("the requested role rows are locked (SELECT ... FOR UPDATE, name order) before any role is read")
    void lockedFirst() throws Exception {
        User agent = h.eligibleStaff("tariro@innbucks.co.zw", "CALL_CENTER_AGENT");
        setRoles(agent, "[\"FRAUD_DESK\",\"CALL_CENTER_AGENT\"]").andExpect(status().isOk());
        var order = inOrder(h.roles);
        order.verify(h.roles).lockAllByNameIn(eq(new java.util.TreeSet<>(List.of("CALL_CENTER_AGENT", "FRAUD_DESK"))));
        order.verify(h.roles, org.mockito.Mockito.atLeastOnce()).findAllByNameIn(anyCollection());
    }
}
