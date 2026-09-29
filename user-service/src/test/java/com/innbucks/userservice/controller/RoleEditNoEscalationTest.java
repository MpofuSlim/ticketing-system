package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adding a code to a role needs the caller to hold it — resolved from the
 * caller's LIVE roles, never from the token.
 *
 * <p>The caller here is the shape an earlier release could produce: a custom
 * role that was granted {@code roles:write} before it became reserved (the
 * pre-deploy query flags these). Such a caller can still edit roles, but only
 * ever within what they hold themselves.
 */
class RoleEditNoEscalationTest {

    private static final String EDITOR = "role.editor@innbucks.co.zw";

    private AdminDispatchHarness h;
    private User editor;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.role("LEGACY_ROLE_EDITOR", "roles:write", "roles:read", "device-security:read");
        editor = h.account(20L, EDITOR, "LEGACY_ROLE_EDITOR");
        h.role("SUPPORT_VIEWER", "device-security:read");
    }

    @Test
    @DisplayName("a code the caller holds may be added")
    void heldCodeIsGranted() throws Exception {
        h.mvc.perform(post("/admin/roles").principal(as(EDITOR, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"PHONE_LOOKUP","description":"Looks phones up","permissions":["device-security:read"]}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a code the caller does not hold is refused, on create and on edit")
    void unheldCodeIsRefused() throws Exception {
        h.mvc.perform(post("/admin/roles").principal(as(EDITOR, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"PHONE_ADMIN","description":"d","permissions":["device-security:read","device-security:manage"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "These permissions can't be granted here: device-security:manage (grants more than you hold)."))
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"))
                .andExpect(jsonPath("$.data.codes['device-security:manage']").value("exceeds_your_authority"));

        h.mvc.perform(put("/admin/roles/SUPPORT_VIEWER/permissions").principal(as(EDITOR, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"device-security:read\",\"users:read\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.codes['users:read']").value("exceeds_your_authority"));
        assertThat(h.roleRows.get("SUPPORT_VIEWER").getPermissions()).containsExactly("device-security:read");
    }

    @Test
    @DisplayName("the check reads the caller's LIVE roles — authority on the token does not count")
    void liveNotToken() throws Exception {
        // The token still claims device-security:manage (minted before an
        // operator trimmed the caller's role); the live role no longer grants it.
        h.mvc.perform(put("/admin/roles/SUPPORT_VIEWER/permissions")
                        .principal(as(EDITOR, "roles:write", "device-security:manage"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"device-security:read\",\"device-security:manage\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.codes['device-security:manage']").value("exceeds_your_authority"));

        // And the other way round: granted on the live role since the token was
        // minted, so it is held — no refresh needed to use it.
        h.role("LEGACY_ROLE_EDITOR", "roles:write", "roles:read", "device-security:read", "device-security:manage");
        h.mvc.perform(put("/admin/roles/SUPPORT_VIEWER/permissions").principal(as(EDITOR, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"device-security:read\",\"device-security:manage\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an edit that only removes codes is never refused — whatever the caller holds")
    void removalOnlyIsNeverRefused() throws Exception {
        h.role("ACCOUNT_AUDITOR", "users:read", "users:merchants:read");

        h.mvc.perform(put("/admin/roles/ACCOUNT_AUDITOR/permissions").principal(as(EDITOR, "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"users:merchants:read\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[0]").value("users:merchants:read"));
    }

    @Test
    @DisplayName("a caller that no longer resolves to an active account holds nothing")
    void unresolvedCallerHoldsNothing() throws Exception {
        editor.setActive(false);

        h.mvc.perform(post("/admin/roles").principal(as(EDITOR, "roles:write", "device-security:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"PHONE_LOOKUP","description":"d","permissions":["device-security:read"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.codes['device-security:read']").value("exceeds_your_authority"));

        h.mvc.perform(post("/admin/roles").principal(as("nobody@innbucks.co.zw", "roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"PHONE_LOOKUP","description":"d","permissions":["device-security:read"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("permission_not_assignable"));
    }
}
