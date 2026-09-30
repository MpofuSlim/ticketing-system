package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staff account never joins a business (V44): adding one, or changing the
 * role of one already in, is 409 {@code staff_account_not_eligible} — a support
 * agent must never also be a merchant or a seller. And the platform resolution
 * for an organization console-created "staff" own: suspend it.
 */
class OrganizationAddMemberRefusesStaffTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    @Test
    @DisplayName("adding a staff-role holder or a profiled account: 409; a customer is added as before")
    void addMember() throws Exception {
        User owner = h.account("rudo@shop.co.zw", "MERCHANT_ADMIN");
        Organization org = h.organizationOf(owner, OrganizationMember.Role.OWNER);
        h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        h.mvc.perform(post("/organizations/{id}/members", org.getId()).principal(StaffDispatchHarness.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"tariro.moyo@innbucks.co.zw\",\"role\":\"STAFF\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("InnBucks staff accounts can't join a business or request products."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_account_not_eligible"));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(owner.getEmail()), any(), any(),
                any(), eq("staff_account_not_eligible"), any(), any());

        h.account("colleague@example.com", "CUSTOMER");
        h.mvc.perform(post("/organizations/{id}/members", org.getId()).principal(StaffDispatchHarness.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"colleague@example.com\",\"role\":\"STAFF\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("changing the role of a staff account already in (a legacy row): 409 — it can be removed, never re-empowered")
    void changeRole() throws Exception {
        User owner = h.account("rudo@shop.co.zw", "MERCHANT_ADMIN");
        Organization org = h.organizationOf(owner, OrganizationMember.Role.OWNER);
        User legacy = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        h.memberRows.add(OrganizationMember.builder().id(java.util.UUID.randomUUID()).organizationId(org.getId())
                .userId(legacy.getId()).role(OrganizationMember.Role.STAFF)
                .createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).build());
        h.mvc.perform(put("/organizations/{id}/members/{uuid}", org.getId(), legacy.getUserUuid())
                        .principal(StaffDispatchHarness.asUser(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("staff_account_not_eligible"));
    }

    @Test
    @DisplayName("POST /admin/organizations/{id}/suspend: SUSPENDED, every member signed out, audited; 409 twice")
    void suspend() throws Exception {
        User owner = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        Organization org = h.organizationOf(owner, OrganizationMember.Role.OWNER);
        h.mvc.perform(post("/admin/organizations/{id}/suspend", org.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Created by mistake through the console (OPS-1187).\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Organization suspended"))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.membersSignedOut").value(1));
        assertThat(org.getStatus()).isEqualTo(Organization.Status.SUSPENDED);
        assertThat(owner.getTokenVersion()).isEqualTo(4L);
        assertThat(h.eligibility.hasActiveOrganization(owner)).isFalse();
        // ...so the legacy account is now adoptable.
        assertThat(h.eligibility.adoptionBlocker(owner)).isEmpty();
        verify(h.audit).recordRequired(eq(AuditEventType.ORGANIZATION_SUSPENDED), eq(OWNER), any(),
                eq(org.getId().toString()), eq("ORGANIZATION"), any(), any());

        h.mvc.perform(post("/admin/organizations/{id}/suspend", org.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("organization_not_active"));
        h.mvc.perform(post("/admin/organizations/{id}/suspend", java.util.UUID.randomUUID()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound());
        h.mvc.perform(post("/admin/organizations/{id}/suspend", org.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.note").value("note is required"));
    }
}
