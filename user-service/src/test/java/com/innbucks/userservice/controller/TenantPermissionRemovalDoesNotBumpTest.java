package com.innbucks.userservice.controller;

import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.util.Map;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which permission removals sign a role's holders out at once.
 *
 * <p>Removing a PLATFORM code bumps every holder (a support agent who loses
 * {@code device-security:manage} must not keep using it for the rest of their
 * access token's life). Removing only TENANT codes does NOT: the change reaches
 * each holder at their next refresh, so trimming what every MERCHANT_ADMIN can
 * do does not sign every business on the platform out at once. The real
 * statement and the Redis publish are proved against Postgres + Redis by
 * {@code PlatformPermissionRemovalBumpsHoldersIT}.
 */
class TenantPermissionRemovalDoesNotBumpTest {

    private static final String OWNER = "admin@innbucks.co.zw";

    private AdminDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.account(1L, OWNER, "SUPER_ADMIN");
        h.bumper.holdersPerRole = 2;
    }

    private void setPermissions(String role, String json) throws Exception {
        h.mvc.perform(put("/admin/roles/" + role + "/permissions").principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(h.audit).recordRequired(eq(AuditEventType.ROLE_PERMISSIONS_CHANGED), eq(OWNER),
                eq(AuditService.ACTOR_TYPE_USER), any(), eq(AuditService.TARGET_TYPE_ROLE),
                detail.capture(), any());
        return detail.getValue();
    }

    @Test
    @DisplayName("removing only TENANT codes from MERCHANT_ADMIN signs nobody out")
    void tenantRemovalDoesNotBump() throws Exception {
        setPermissions("MERCHANT_ADMIN", "{\"permissions\":[\"shop-admins:write\",\"shop-staff:read\"]}");

        assertThat(h.bumper.bumpedRoles).isEmpty();
        assertThat(auditDetail())
                .containsEntry("platformPermissionRemoved", false)
                .containsEntry("holdersSignedOut", 0);
    }

    @Test
    @DisplayName("removing a PLATFORM code bumps every holder of the role, and the audit row says how many")
    void platformRemovalBumps() throws Exception {
        setPermissions("CALL_CENTER_AGENT", "{\"permissions\":[\"device-security:read\"]}");

        assertThat(h.bumper.bumpedRoles).containsExactly("CALL_CENTER_AGENT");
        assertThat(auditDetail())
                .containsEntry("platformPermissionRemoved", true)
                .containsEntry("holdersSignedOut", 2);
    }

    @Test
    @DisplayName("ADDING a code signs nobody out")
    void additionDoesNotBump() throws Exception {
        setPermissions("CALL_CENTER_AGENT",
                "{\"permissions\":[\"device-security:read\",\"device-security:manage\","
                        + "\"support-console:read\",\"support-console:manage\",\"users:read\"]}");
        assertThat(h.bumper.bumpedRoles).isEmpty();
    }

    @Test
    @DisplayName("removing a stale code the catalog no longer defines bumps — it classifies as PLATFORM")
    void staleCodeRemovalFailsClosed() throws Exception {
        h.role("LEGACY_REFUNDS", "refunds:approve", "shop-staff:read");

        setPermissions("LEGACY_REFUNDS", "{\"permissions\":[\"shop-staff:read\"]}");
        assertThat(h.bumper.bumpedRoles).containsExactly("LEGACY_REFUNDS");
    }
}
