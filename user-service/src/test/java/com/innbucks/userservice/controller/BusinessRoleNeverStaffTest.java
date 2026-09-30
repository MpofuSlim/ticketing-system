package com.innbucks.userservice.controller;

import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.security.StaffRoles;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.util.List;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A business built-in never becomes a staff role (V44): registration +
 * approval, shop-staff and team-member create and the customer sign-ups hand
 * EVENT_ORGANIZER / MERCHANT_ADMIN / SHOP_ADMIN / SHOP_USER / TEAM_MEMBER /
 * CUSTOMER out with no staff-eligibility check, so a PLATFORM code on one would
 * reach every account those paths create. Refused whoever asks — SUPER_ADMIN
 * included — with 400 {@code permission_not_assignable}, reason
 * {@code business_role}. TENANT codes and removals are unaffected.
 */
class BusinessRoleNeverStaffTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    @ParameterizedTest
    @ValueSource(strings = {"EVENT_ORGANIZER", "MERCHANT_ADMIN", "SHOP_ADMIN", "SHOP_USER", "TEAM_MEMBER", "CUSTOMER"})
    @DisplayName("adding a PLATFORM code to a business built-in: 400 business_role, even for SUPER_ADMIN")
    void platformCodeRefused(String role) throws Exception {
        List<String> before = List.copyOf(h.roleRows.get(role).getPermissions());
        String keep = before.isEmpty() ? "" : "\"" + String.join("\",\"", before) + "\",";
        h.mvc.perform(put("/admin/roles/{name}/permissions", role).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[" + keep + "\"users:read\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("These permissions can't be granted here: users:read "
                        + "(a business role can't hold platform permissions)."))
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"))
                .andExpect(jsonPath("$.data.codes['users:read']").value("business_role"));
        assertThat(h.roleRows.get(role).getPermissions()).containsExactlyInAnyOrderElementsOf(before);
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), eq(OWNER), any(), any(), any(),
                eq("permission_not_assignable"), any(), any());
    }

    @Test
    @DisplayName("a TENANT code on a business built-in is still fine")
    void tenantCodeAllowed() throws Exception {
        List<String> before = List.copyOf(h.roleRows.get("MERCHANT_ADMIN").getPermissions());
        h.mvc.perform(put("/admin/roles/MERCHANT_ADMIN/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"" + String.join("\",\"", before) + "\",\"team-members:read\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the guard itself (create and edit share it): the wildcard and an unknown code count as PLATFORM")
    void guardDirect() {
        RoleGrantGuard.Caller owner = h.guard.resolveCaller(OWNER);
        for (String role : StaffRoles.BUSINESS_BUILT_INS) {
            assertThatThrownBy(() -> h.guard.requireMayGrant(owner, List.of("*", "no-such:code"), role))
                    .isInstanceOf(StaffPolicyException.class)
                    .extracting("extra").asString()
                    .contains("*=business_role").contains("no-such:code=business_role");
        }
        // A custom role may still become a staff role (its holders are then checked).
        h.guard.requireMayGrant(owner, List.of("users:read"), "SUPPORT_VIEWER");
    }
}
