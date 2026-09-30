package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.AdminDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.AdminDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/roles}: every role ADDED must grant only what the
 * caller holds, and a NAMED staff role (PRODUCT_*, CALL_CENTER_*, FRAUD_DESK)
 * additionally needs the caller to hold that role or the wildcard — booking and
 * event grant access by the NAME, so comparing permissions alone would not bound
 * what it hands out.
 *
 * <p>The granting caller holds a legacy role that was given
 * {@code users:roles:write} before it became reserved to the wildcard.
 */
class NamedRoleAssignmentTest {

    private static final String GRANTER = "supervisor.lead@innbucks.co.zw";
    private static final String OWNER = "admin@innbucks.co.zw";

    private AdminDispatchHarness h;
    private User target;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.role("LEGACY_GRANTER", "users:roles:write", "users:merchants:read");
        h.account(30L, GRANTER, "LEGACY_GRANTER", "CALL_CENTER_SUPERVISOR");
        h.account(1L, OWNER, "SUPER_ADMIN");
        // A staff-eligible target (V44): email proven, accepted staff profile —
        // a staff role can only be ADDED to such an account, and a profiled
        // account holds staff roles only, so its baseline role is one too.
        target = h.eligibleStaff(h.account(40L, "tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT"));
    }

    private org.springframework.test.web.servlet.ResultActions setRoles(String caller, String json)
            throws Exception {
        return h.mvc.perform(put("/admin/users/40/roles").principal(as(caller, "users:roles:write"))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("a NAMED role the caller does not hold is refused even when its permissions are within theirs")
    void namedRoleNotHeld() throws Exception {
        // PRODUCT_OFFICER grants only users:merchants:read, which the granter holds.
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"PRODUCT_OFFICER\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "These roles can't be given to this account: PRODUCT_OFFICER "
                                + "(only someone who holds this role can give it)."))
                .andExpect(jsonPath("$.data.errorCode").value("role_not_assignable"))
                .andExpect(jsonPath("$.data.roles.PRODUCT_OFFICER").value("named_role_not_held"));

        assertThat(target.getRoles()).containsExactly("CALL_CENTER_AGENT");
        verify(h.audit, never()).recordRequired(eq(AuditEventType.USER_ROLES_CHANGED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a NAMED role the caller holds may be given")
    void namedRoleHeld() throws Exception {
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"CALL_CENTER_SUPERVISOR\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.hasItem("CALL_CENTER_SUPERVISOR")));
        verify(h.audit).recordRequired(eq(AuditEventType.USER_ROLES_CHANGED),
                eq(GRANTER), any(), eq("40"), any(), any(), any());
    }

    @Test
    @DisplayName("every refused role is named with its reason, in one answer")
    void everyRefusalNamed() throws Exception {
        // FRAUD_DESK grants device-security:fraud, which the granter lacks — that
        // is reported before the NAMED rule, which would refuse it as well.
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"FRAUD_DESK\",\"PRODUCT_OFFICER\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "These roles can't be given to this account: FRAUD_DESK (grants more than you hold), "
                                + "PRODUCT_OFFICER (only someone who holds this role can give it)."))
                .andExpect(jsonPath("$.data.roles.FRAUD_DESK").value("exceeds_your_authority"))
                .andExpect(jsonPath("$.data.roles.PRODUCT_OFFICER").value("named_role_not_held"));
    }

    @Test
    @DisplayName("a custom role granting more than the caller holds is refused")
    void customRoleExceedingTheCaller() throws Exception {
        h.role("ACCOUNT_AUDITOR", "users:read");
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"ACCOUNT_AUDITOR\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.roles.ACCOUNT_AUDITOR").value("exceeds_your_authority"));
    }

    @Test
    @DisplayName("the platform owner may give every built-in staff role")
    void wildcardMayGiveEveryNamedRole() throws Exception {
        setRoles(OWNER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"FRAUD_DESK\",\"PRODUCT_MANAGER\"]}")
                .andExpect(status().isOk());
        assertThat(target.getRoles())
                .containsExactlyInAnyOrder("CALL_CENTER_AGENT", "FRAUD_DESK", "PRODUCT_MANAGER");
    }

    @Test
    @DisplayName("a legacy role STORING a code reserved to the wildcard cannot be spread, even by its own holder")
    void legacyReservedCodeRoleIsNotAssignable() throws Exception {
        // LEGACY_GRANTER stores users:roles:write from before that code was
        // reserved. The granter holds the role and so every code in it — the
        // permission comparison alone would wave it through.
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"LEGACY_GRANTER\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "These roles can't be given to this account: LEGACY_GRANTER (reserved to SUPER_ADMIN)."))
                .andExpect(jsonPath("$.data.errorCode").value("role_not_assignable"))
                .andExpect(jsonPath("$.data.roles.LEGACY_GRANTER").value("reserved_to_super_admin"));

        assertThat(target.getRoles()).containsExactly("CALL_CENTER_AGENT");
    }

    @Test
    @DisplayName("the platform owner may still give a legacy role that stores a reserved code")
    void wildcardMayGiveALegacyReservedCodeRole() throws Exception {
        setRoles(OWNER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"LEGACY_GRANTER\"]}")
                .andExpect(status().isOk());
        assertThat(target.getRoles()).containsExactlyInAnyOrder("CALL_CENTER_AGENT", "LEGACY_GRANTER");
    }

    @Test
    @DisplayName("a role storing a code the catalog no longer defines counts as granting more than the caller holds")
    void staleCodeCountsAsNotHeld() throws Exception {
        // refunds:approve is not in the catalog: it grants nobody anything, so
        // the granter does not hold it — and it must not be read as "nothing to
        // compare" (an unknown code classifies as PLATFORM, failing closed).
        h.role("STALE_ROLE", "users:merchants:read", "refunds:approve");
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"STALE_ROLE\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.roles.STALE_ROLE").value("exceeds_your_authority"));

        setRoles(OWNER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"STALE_ROLE\"]}")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a role the account already holds is not re-checked — only ADDED roles are")
    void onlyAddedRolesAreChecked() throws Exception {
        target.getRoles().add("PRODUCT_OFFICER");
        // The granter could never have given PRODUCT_OFFICER, but keeping it while
        // adding one they may give is not a grant of it.
        setRoles(GRANTER, "{\"roles\":[\"CALL_CENTER_AGENT\",\"PRODUCT_OFFICER\",\"CALL_CENTER_SUPERVISOR\"]}")
                .andExpect(status().isOk());
    }
}
