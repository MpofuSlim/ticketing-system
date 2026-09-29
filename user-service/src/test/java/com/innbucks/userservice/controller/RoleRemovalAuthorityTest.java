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
 * permission the account holds. Otherwise a narrower administrator could strip,
 * lock out or open up a broader one: a support lead holding
 * {@code users:activation:write} switching off the product manager above them.
 *
 * <p>All three answer {@code 403 target_not_manageable}, {@code reason:
 * exceeds_your_authority}, and change nothing.
 */
class RoleRemovalAuthorityTest {

    /** Holds a legacy grant of the admin-surface codes, plus device-security read/manage. */
    private static final String LEAD = "support.lead@innbucks.co.zw";

    private AdminDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new AdminDispatchHarness();
        h.role("SUPPORT_LEAD", "users:roles:write", "users:activation:write", "users:mfa:reset",
                "device-security:read", "device-security:manage");
        h.account(30L, LEAD, "SUPPORT_LEAD", "CALL_CENTER_SUPERVISOR");
    }

    @Test
    @DisplayName("removing a role from an account holding more than the caller: 403, nothing changes")
    void removalNeedsAuthorityOverTheWholeAccount() throws Exception {
        // The product manager holds users:merchants:read, which the lead does not.
        User pm = h.account(41L, "pm@innbucks.co.zw", "PRODUCT_MANAGER", "CALL_CENTER_AGENT");
        long before = pm.getTokenVersion();

        h.mvc.perform(put("/admin/users/41/roles").principal(as(LEAD, "users:roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"CALL_CENTER_AGENT\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403 FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("You can't change this account."))
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("exceeds_your_authority"));

        assertThat(pm.getRoles()).containsExactlyInAnyOrder("PRODUCT_MANAGER", "CALL_CENTER_AGENT");
        assertThat(pm.getTokenVersion()).isEqualTo(before);
        verify(h.audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("removing a role from an account within the caller's authority succeeds")
    void removalWithinAuthority() throws Exception {
        User agent = h.account(42L, "agent@innbucks.co.zw", "CALL_CENTER_AGENT", "CUSTOMER");

        h.mvc.perform(put("/admin/users/42/roles").principal(as(LEAD, "users:roles:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isOk());
        assertThat(agent.getRoles()).containsExactly("CUSTOMER");
    }

    @Test
    @DisplayName("deactivating a staff-role holder with more authority: 403; the account stays on")
    void deactivatingABroaderStaffMember() throws Exception {
        User fraud = h.account(43L, "fraud@innbucks.co.zw", "FRAUD_DESK");

        h.mvc.perform(put("/admin/users/43/active").principal(as(LEAD, "users:activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("exceeds_your_authority"));

        assertThat(fraud.isActive()).isTrue();
        assertThat(h.bumper.bumpedRoles).isEmpty();
    }

    @Test
    @DisplayName("deactivating a staff-role holder within the caller's authority succeeds")
    void deactivatingANarrowerStaffMember() throws Exception {
        User agent = h.account(44L, "agent2@innbucks.co.zw", "CALL_CENTER_AGENT");

        h.mvc.perform(put("/admin/users/44/active").principal(as(LEAD, "users:activation:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        assertThat(agent.isActive()).isFalse();
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

        h.mvc.perform(post("/admin/users/46/mfa/reset").principal(as(LEAD, "users:mfa:reset")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("exceeds_your_authority"));

        assertThat(fraud.isMfaEnabled()).isTrue();
        assertThat(fraud.getMfaSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    @DisplayName("resetting the 2FA of an account within the caller's authority succeeds")
    void mfaResetWithinAuthority() throws Exception {
        User agent = h.account(47L, "agent3@innbucks.co.zw", "CALL_CENTER_AGENT");
        agent.setMfaEnabled(true);
        agent.setMfaSecret("JBSWY3DPEHPK3PXP");

        h.mvc.perform(post("/admin/users/47/mfa/reset").principal(as(LEAD, "users:mfa:reset")))
                .andExpect(status().isOk());
        assertThat(agent.isMfaEnabled()).isFalse();
    }
}
