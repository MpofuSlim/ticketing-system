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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staff account never joins a business (V44). Adding one is answered exactly
 * like an unknown email (404 {@code account_not_found}), so a business owner
 * cannot probe which accounts are InnBucks staff; the refusal is still audited.
 * An InnBucks ADDRESS is not refused for that alone: an ordinary account on one
 * is added like any other (owner decision, 2026-10-08). Changing the role of one
 * already in (a legacy row) is 409 {@code staff_account_not_eligible}. And the platform resolution
 * for an organization console-created "staff" own: suspend it.
 */
class OrganizationAddMemberRefusesStaffTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    @Test
    @DisplayName("adding a staff account: the same 404 as an unknown email, still audited; an ordinary InnBucks-address account is added")
    void addMember() throws Exception {
        User owner = h.account("rudo@shop.co.zw", "MERCHANT_ADMIN");
        Organization org = h.organizationOf(owner, OrganizationMember.Role.OWNER);
        h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        h.account("legacy.po@gmail.com", "PRODUCT_OFFICER");   // an off-domain staff-role holder

        // A staff account, an off-domain staff account and two unknown
        // addresses (one on an InnBucks domain) all read identically.
        for (String email : new String[]{"tariro.moyo@innbucks.co.zw", "nobody@innbucks.co.ke",
                "legacy.po@gmail.com", "nobody@example.com"}) {
            h.mvc.perform(post("/organizations/{id}/members", org.getId()).principal(StaffDispatchHarness.asUser(owner))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"role\":\"STAFF\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(
                            "There's no account with that email. Ask them to register first, then add them."))
                    .andExpect(jsonPath("$.data.errorCode").value("account_not_found"));
        }
        // ...but the two staff accounts are on the audit chain; the unknown addresses are not.
        verify(h.audit, times(2)).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED),
                eq(owner.getEmail()), any(), any(), any(), eq("staff_account_not_eligible"), any(), any());
        assertThat(h.memberRows).hasSize(1);

        // An ordinary account on an InnBucks address joins like any other.
        h.account("gclerkson@innbucks.co.zw", "MERCHANT_ADMIN");
        h.mvc.perform(post("/organizations/{id}/members", org.getId()).principal(StaffDispatchHarness.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"gclerkson@innbucks.co.zw\",\"role\":\"STAFF\"}"))
                .andExpect(status().isCreated());

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
                        .header("X-Forwarded-For", "41.79.10.22, 10.0.0.5")
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
        // The audit row records the CLIENT's address (leftmost X-Forwarded-For), not the gateway pod's.
        verify(h.audit).recordRequired(eq(AuditEventType.ORGANIZATION_SUSPENDED), eq(OWNER), any(),
                eq(org.getId().toString()), eq("ORGANIZATION"), any(),
                argThat(c -> "41.79.10.22".equals(c.ipAddress())));

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
