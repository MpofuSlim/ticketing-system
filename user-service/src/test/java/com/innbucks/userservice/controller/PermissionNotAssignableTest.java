package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The codes that hand out authority — {@code roles:write}, {@code users:roles:write},
 * {@code staff:*}, {@code organizations:manage} — can never be granted through the
 * API: not to a custom role, not to a built-in, and not by SUPER_ADMIN.
 *
 * <p>Before this, anyone holding {@code roles:write} could add {@code roles:write}
 * (and everything else) to a role they held — self-escalation by role edit.
 */
class PermissionNotAssignableTest {

    private static final String OWNER = "admin@innbucks.co.zw";

    private AdminDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.account(1L, OWNER, "SUPER_ADMIN");
    }

    @Test
    @DisplayName("a new role holding roles:write is refused, even for SUPER_ADMIN")
    void createWithRolesWrite_refused() throws Exception {
        h.mvc.perform(post("/admin/roles").principal(as(OWNER, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ROLE_EDITOR","description":"Edits roles","permissions":["roles:write","users:read"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400 BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "These permissions can't be granted here: roles:write (reserved to SUPER_ADMIN)."))
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"))
                .andExpect(jsonPath("$.data.codes['roles:write']").value("reserved_to_super_admin"))
                .andExpect(jsonPath("$.data.codes['users:read']").doesNotExist());

        assertThat(h.roleRows).doesNotContainKey("ROLE_EDITOR");
        verify(h.roles, never()).save(any(Role.class));
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("users:roles:write and the staff:* codes are refused the same way, each named")
    void everyReservedCodeIsNamed() throws Exception {
        h.mvc.perform(post("/admin/roles").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"GRANTER","description":"d","permissions":["users:roles:write","staff:create","organizations:manage"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"))
                // Reserved is checked before "unknown": staff:* and organizations:manage
                // are reserved from the release that names them, before any endpoint does.
                .andExpect(jsonPath("$.data.codes['users:roles:write']").value("reserved_to_super_admin"))
                .andExpect(jsonPath("$.data.codes['staff:create']").value("reserved_to_super_admin"))
                .andExpect(jsonPath("$.data.codes['organizations:manage']").value("reserved_to_super_admin"));
    }

    @Test
    @DisplayName("adding roles:write to a BUILT-IN role is refused too")
    void addingToABuiltIn_refused() throws Exception {
        h.mvc.perform(put("/admin/roles/PRODUCT_MANAGER/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"users:merchants:read\",\"roles:write\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"))
                .andExpect(jsonPath("$.data.codes['roles:write']").value("reserved_to_super_admin"));

        assertThat(h.roleRows.get("PRODUCT_MANAGER").getPermissions()).containsExactly("users:merchants:read");
    }

    @Test
    @DisplayName("adding users:roles:write to a custom role is refused")
    void addingToACustomRole_refused() throws Exception {
        h.role("ACCOUNT_AUDITOR", "users:read");

        h.mvc.perform(put("/admin/roles/ACCOUNT_AUDITOR/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"users:read\",\"users:roles:write\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.codes['users:roles:write']").value("reserved_to_super_admin"));
    }

    @Test
    @DisplayName("a role that already holds a reserved code keeps it on an edit that only removes something")
    void legacyHoldingSurvivesARemovalOnlyEdit() throws Exception {
        // An earlier release let an operator grant roles:write to a custom role.
        // The pre-deploy query flags those; they are left in place, and taking
        // other codes away from the role must still work.
        h.role("LEGACY_ROLE_EDITOR", "roles:write", "users:read");

        h.mvc.perform(put("/admin/roles/LEGACY_ROLE_EDITOR/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"roles:write\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[0]").value("roles:write"));
    }

    @Test
    @DisplayName("ordinary codes the platform owner holds are granted as before")
    void ordinaryCodesStillGrant() throws Exception {
        h.mvc.perform(post("/admin/roles").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"REFUND_OFFICER","description":"Refunds","permissions":["users:read","users:password:reset"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("REFUND_OFFICER"));
    }
}
