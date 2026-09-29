package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
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
 * Acting AGAINST an account — removing one of its roles, switching off a
 * staff-role holder, resetting its 2FA — needs the caller to hold every
 * permission the account holds AND every NAMED staff role it holds (or the
 * wildcard). Otherwise a narrower administrator could strip, lock out or open up
 * a broader one: a support lead holding {@code users:activation:write}
 * switching off the product manager above them.
 *
 * <p>The NAME half matters because {@code PRODUCT_MANAGER} resolves to a single
 * read-only code here ({@code users:merchants:read}) while its real authority is
 * its name in event-service and booking-service. The lead below holds that code
 * deliberately, so every product-manager case is refused by the name rule and
 * not by an accident of the permission comparison.
 *
 * <p>All refusals answer {@code 403 target_not_manageable}, {@code reason:
 * exceeds_your_authority}, and change nothing.
 */
class RoleRemovalAuthorityTest {

    /**
     * Holds a legacy grant of the admin-surface codes, device-security
     * read/manage, and {@code users:merchants:read} — everything PRODUCT_MANAGER
     * resolves to — plus the NAMED role CALL_CENTER_SUPERVISOR.
     */
    private static final String LEAD = "support.lead@innbucks.co.zw";
    private static final String OWNER = "admin@innbucks.co.zw";

    private AdminDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.role("SUPPORT_LEAD", "users:roles:write", "users:activation:write", "users:mfa:reset",
                "device-security:read", "device-security:manage", "users:merchants:read");
        h.role("DEVICE_VIEWER", "device-security:read");
        h.role("ACCOUNT_AUDITOR", "users:read");
        h.account(30L, LEAD, "SUPPORT_LEAD", "CALL_CENTER_SUPERVISOR");
        h.account(1L, OWNER, "SUPER_ADMIN");
    }

    private void assertRefused(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You can't change this account."))
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("exceeds_your_authority"));
    }

    // -- the permission half ---------------------------------------------------

    @Test
    @DisplayName("removing a role from an account holding a permission the caller lacks: 403, nothing changes")
    void removalNeedsAuthorityOverTheWholeAccount() throws Exception {
        // users:read — the lead does not hold it. The role being removed is one
        // the lead could give; the refusal is about the ACCOUNT.
        User auditor = h.account(41L, "auditor@innbucks.co.zw", "ACCOUNT_AUDITOR", "CUSTOMER");
        long before = auditor.getTokenVersion();

        assertRefused(h.mvc.perform(put("/admin/users/41/roles").principal(as(LEAD, "users:roles:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"ACCOUNT_AUDITOR\"]}")));

        assertThat(auditor.getRoles()).containsExactlyInAnyOrder("ACCOUNT_AUDITOR", "CUSTOMER");
        assertThat(auditor.getTokenVersion()).isEqualTo(before);
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("removing a role from an account within the caller's authority succeeds")
    void removalWithinAuthority() throws Exception {
        // A peer: CALL_CENTER_SUPERVISOR is a NAMED role the lead holds.
        User peer = h.account(42L, "peer@innbucks.co.zw", "CALL_CENTER_SUPERVISOR", "CUSTOMER");

        h.mvc.perform(put("/admin/users/42/roles").principal(as(LEAD, "users:roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isOk());
        assertThat(peer.getRoles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("deactivating a staff-role holder with more authority: 403; the account stays on")
    void deactivatingABroaderStaffMember() throws Exception {
        User fraud = h.account(43L, "fraud@innbucks.co.zw", "FRAUD_DESK");

        assertRefused(h.mvc.perform(put("/admin/users/43/active").principal(as(LEAD, "users:activation:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")));

        assertThat(fraud.isActive()).isTrue();
        assertThat(h.bumper.bumpedRoles).isEmpty();
    }

    @Test
    @DisplayName("deactivating a staff-role holder within the caller's authority succeeds")
    void deactivatingANarrowerStaffMember() throws Exception {
        User viewer = h.account(44L, "viewer@innbucks.co.zw", "DEVICE_VIEWER");

        h.mvc.perform(put("/admin/users/44/active").principal(as(LEAD, "users:activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        assertThat(viewer.isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivating a BUSINESS account is not gated on authority — it is not staff")
    void businessAccountsAreNotGated() throws Exception {
        // MERCHANT_ADMIN holds shop-* codes the lead does not; its authority
        // reaches one business, not the platform.
        User merchant = h.account(45L, "owner@shop.co.zw", "MERCHANT_ADMIN");

        h.mvc.perform(put("/admin/users/45/active").principal(as(LEAD, "users:activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        assertThat(merchant.isActive()).isFalse();
    }

    @Test
    @DisplayName("resetting the 2FA of an account holding more than the caller: 403, the secret is kept")
    void mfaResetNeedsAuthority() throws Exception {
        User fraud = h.account(46L, "fraud2@innbucks.co.zw", "FRAUD_DESK");
        fraud.setMfaEnabled(true);
        fraud.setMfaSecret("JBSWY3DPEHPK3PXP");

        assertRefused(h.mvc.perform(post("/admin/users/46/mfa/reset").principal(as(LEAD, "users:mfa:reset"))));

        assertThat(fraud.isMfaEnabled()).isTrue();
        assertThat(fraud.getMfaSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    @DisplayName("resetting the 2FA of an account within the caller's authority succeeds")
    void mfaResetWithinAuthority() throws Exception {
        User viewer = h.account(47L, "viewer2@innbucks.co.zw", "DEVICE_VIEWER");
        viewer.setMfaEnabled(true);
        viewer.setMfaSecret("JBSWY3DPEHPK3PXP");

        h.mvc.perform(post("/admin/users/47/mfa/reset").principal(as(LEAD, "users:mfa:reset")))
                .andExpect(status().isOk());
        assertThat(viewer.isMfaEnabled()).isFalse();
    }

    // -- the NAME half ---------------------------------------------------------

    @Test
    @DisplayName("stripping a PRODUCT_MANAGER: 403 even though the caller holds every code it resolves to")
    void productManagerCannotBeStrippedByAHolderOfItsCodes() throws Exception {
        User pm = h.account(50L, "pm@innbucks.co.zw", "PRODUCT_MANAGER", "CUSTOMER");
        long before = pm.getTokenVersion();

        assertRefused(h.mvc.perform(put("/admin/users/50/roles").principal(as(LEAD, "users:roles:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"CUSTOMER\"]}")));

        assertThat(pm.getRoles()).containsExactlyInAnyOrder("PRODUCT_MANAGER", "CUSTOMER");
        assertThat(pm.getTokenVersion()).isEqualTo(before);
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("removing ANY role from a PRODUCT_MANAGER's account is refused, not only the named one")
    void productManagerAccountIsProtectedWhole() throws Exception {
        User pm = h.account(51L, "pm2@innbucks.co.zw", "PRODUCT_MANAGER", "CUSTOMER");

        assertRefused(h.mvc.perform(put("/admin/users/51/roles").principal(as(LEAD, "users:roles:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[\"PRODUCT_MANAGER\"]}")));

        assertThat(pm.getRoles()).containsExactlyInAnyOrder("PRODUCT_MANAGER", "CUSTOMER");
    }

    @Test
    @DisplayName("deactivating a PRODUCT_MANAGER: 403 by name; the account stays on and no session ends")
    void productManagerCannotBeDeactivatedByAHolderOfItsCodes() throws Exception {
        User pm = h.account(52L, "pm3@innbucks.co.zw", "PRODUCT_MANAGER");

        assertRefused(h.mvc.perform(put("/admin/users/52/active").principal(as(LEAD, "users:activation:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")));

        assertThat(pm.isActive()).isTrue();
        assertThat(h.bumper.bumpedRoles).isEmpty();
    }

    @Test
    @DisplayName("resetting a PRODUCT_MANAGER's 2FA: 403 by name; the secret is kept")
    void productManagerMfaCannotBeResetByAHolderOfItsCodes() throws Exception {
        User pm = h.account(53L, "pm4@innbucks.co.zw", "PRODUCT_MANAGER");
        pm.setMfaEnabled(true);
        pm.setMfaSecret("JBSWY3DPEHPK3PXP");

        assertRefused(h.mvc.perform(post("/admin/users/53/mfa/reset").principal(as(LEAD, "users:mfa:reset"))));

        assertThat(pm.isMfaEnabled()).isTrue();
        assertThat(pm.getMfaSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    @DisplayName("a NAMED role the caller does not hold protects its holder, as it does on assignment")
    void namedRoleNotHeldProtectsItsHolder() throws Exception {
        // CALL_CENTER_AGENT's two codes are both the lead's; the lead holds
        // CALL_CENTER_SUPERVISOR, not CALL_CENTER_AGENT — and could not GIVE it
        // either (NamedRoleAssignmentTest), so it cannot take it away.
        User agent = h.account(54L, "agent@innbucks.co.zw", "CALL_CENTER_AGENT");

        assertRefused(h.mvc.perform(put("/admin/users/54/active").principal(as(LEAD, "users:activation:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")));
        assertThat(agent.isActive()).isTrue();
    }

    @Test
    @DisplayName("a caller who holds PRODUCT_MANAGER may act on another product manager")
    void holdingTheNamedRoleIsEnough() throws Exception {
        h.account(31L, "pm.lead@innbucks.co.zw", "SUPPORT_LEAD", "PRODUCT_MANAGER");
        User pm = h.account(55L, "pm5@innbucks.co.zw", "PRODUCT_MANAGER");

        h.mvc.perform(put("/admin/users/55/active").principal(as("pm.lead@innbucks.co.zw", "users:activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        assertThat(pm.isActive()).isFalse();
    }

    @Test
    @DisplayName("the platform owner may strip, deactivate and reset a product manager")
    void theWildcardManagesEveryNamedRole() throws Exception {
        User pm = h.account(56L, "pm6@innbucks.co.zw", "PRODUCT_MANAGER", "CUSTOMER");
        pm.setMfaEnabled(true);
        pm.setMfaSecret("JBSWY3DPEHPK3PXP");

        h.mvc.perform(post("/admin/users/56/mfa/reset").principal(as(OWNER, "users:mfa:reset")))
                .andExpect(status().isOk());
        h.mvc.perform(put("/admin/users/56/roles").principal(as(OWNER, "users:roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isOk());

        assertThat(pm.isMfaEnabled()).isFalse();
        assertThat(pm.getRoles()).containsExactly("CUSTOMER");
    }
}
