package com.innbucks.userservice.controller;

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
 * Role edits (V44): ADDING a PLATFORM code to a role (or creating a staff role
 * whose name orphan {@code user_roles} strings already hold) needs every holder
 * but SUPER_ADMIN to be staff-eligible — 400 {@code staff_holders_ineligible}.
 * REMOVING codes is never refused on these grounds.
 */
class RolePermissionEditDomainGuardTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    @Test
    @DisplayName("adding a PLATFORM code to a role with unverified holders: 400, naming count, reasons and a sample")
    void addingPlatformCodeRefused() throws Exception {
        h.role("SUPPORT_VIEWER", "shop-staff:read");
        User unverified = h.account("viewer@innbucks.co.zw", "SUPPORT_VIEWER");
        User offDomain = h.account("viewer@gmail.com", "SUPPORT_VIEWER");
        h.eligibleStaff("ok@innbucks.co.zw", "SUPPORT_VIEWER");
        h.mvc.perform(put("/admin/roles/SUPPORT_VIEWER/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"shop-staff:read\",\"users:read\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Some accounts holding this role haven't confirmed an "
                        + "InnBucks email. Invite or remove them first."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_holders_ineligible"))
                .andExpect(jsonPath("$.data.ineligibleHolders").value(2))
                .andExpect(jsonPath("$.data.byReason.unverified").value(1))
                .andExpect(jsonPath("$.data.byReason.off_domain").value(1))
                .andExpect(jsonPath("$.data.sample.length()").value(2));
        assertThat(h.roleRows.get("SUPPORT_VIEWER").getPermissions()).containsExactly("shop-staff:read");
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(OWNER), any(), any(), any(),
                eq("staff_holders_ineligible"), any(), any());
        assertThat(unverified.getId()).isNotEqualTo(offDomain.getId());
    }

    @Test
    @DisplayName("removing a permission from PRODUCT_OFFICER while its holders are unverified: 200")
    void removalNeverRefused() throws Exception {
        h.roleRows.get("PRODUCT_OFFICER").getPermissions().add("users:read");
        h.account("legacy.po@innbucks.co.zw", "PRODUCT_OFFICER");
        h.mvc.perform(put("/admin/roles/PRODUCT_OFFICER/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"users:merchants:read\"]}"))
                .andExpect(status().isOk());
        assertThat(h.roleRows.get("PRODUCT_OFFICER").getPermissions()).containsExactly("users:merchants:read");
    }

    @Test
    @DisplayName("adding a TENANT code to a business role is not a staff grant: no holder check")
    void tenantCodeUnchecked() throws Exception {
        h.account("owner@shop.co.zw", "MERCHANT_ADMIN");
        h.mvc.perform(put("/admin/roles/MERCHANT_ADMIN/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"shop-admins:write\",\"shop-staff:read\","
                                + "\"shop-staff:merchant:read\",\"shop-staff:password:reset\",\"shop-users:write\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("creating a staff role whose name orphan user_roles strings already hold: every holder must be eligible")
    void createWithOrphanHolders() throws Exception {
        h.account("orphan@innbucks.co.zw", "AUDIT_DESK");
        h.mvc.perform(post("/admin/roles").principal(as(OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AUDIT_DESK\",\"description\":\"Reads accounts\","
                                + "\"permissions\":[\"users:read\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("staff_holders_ineligible"))
                .andExpect(jsonPath("$.data.ineligibleHolders").value(1));
        assertThat(h.roleRows).doesNotContainKey("AUDIT_DESK");

        h.mvc.perform(post("/admin/roles").principal(as(OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SHOP_AUDIT\",\"description\":\"Reads shop staff\","
                                + "\"permissions\":[\"shop-staff:read\"]}"))
                .andExpect(status().isCreated());
    }
}
