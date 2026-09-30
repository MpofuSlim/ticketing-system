package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/active} with {@code true} on a staff account is
 * 409 {@code use_staff_endpoints} (V44): a different permission must not skip
 * the domain re-check and the credential reset reactivation performs. Switching
 * one OFF still works (the 1b authority rule applies).
 */
class PutActiveTrueStaffRefusedTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private org.springframework.test.web.servlet.ResultActions setActive(User u, boolean active) throws Exception {
        return h.mvc.perform(put("/admin/users/{id}/active", u.getId()).principal(as(OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}"));
    }

    @Test
    @DisplayName("a deactivated staff-role holder cannot be switched on here")
    void staffRoleHolder() throws Exception {
        User legacy = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        legacy.setActive(false);
        setActive(legacy, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Reactivate staff with POST /admin/staff/{id}/reactivate."))
                .andExpect(jsonPath("$.data.errorCode").value("use_staff_endpoints"));
        assertThat(legacy.isActive()).isFalse();
    }

    @Test
    @DisplayName("a profiled account with no staff role is refused too — the profile alone makes it staff")
    void profiledOnly() throws Exception {
        User profiled = h.account("tariro@innbucks.co.zw", "CUSTOMER");
        profiled.setActive(false);
        h.profileRows.put(profiled.getId(), StaffProfile.builder().userId(profiled.getId())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        setActive(profiled, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("use_staff_endpoints"));
    }

    @Test
    @DisplayName("an active staff account: `true` is refused (no retry-delivery of a temp password), `false` works")
    void activeStaff() throws Exception {
        User agent = h.eligibleStaff("agent@innbucks.co.zw", "CALL_CENTER_AGENT");
        agent.setMustChangePassword(true);
        setActive(agent, true).andExpect(status().isConflict());
        setActive(agent, false).andExpect(status().isOk());
        assertThat(agent.isActive()).isFalse();
    }

    @Test
    @DisplayName("business accounts are unaffected")
    void business() throws Exception {
        User merchant = h.account("owner@shop.co.zw", "MERCHANT_ADMIN");
        merchant.setActive(false);
        setActive(merchant, true).andExpect(status().isOk());
        assertThat(merchant.isActive()).isTrue();
    }
}
