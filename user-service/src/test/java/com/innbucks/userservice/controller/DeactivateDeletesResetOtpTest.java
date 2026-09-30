package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deactivation deletes the live password-reset codes for the account's email
 * AND phone (V44 §2.6.4 step 4), on both routes that deactivate — so a code
 * requested before the deactivation can never plant a password that is waiting
 * for the account after a reactivation.
 */
class DeactivateDeletesResetOtpTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    @Test
    @DisplayName("POST /admin/staff/{id}/deactivate on a legacy staff account: codes by email and by phone deleted")
    void staffEndpoint() throws Exception {
        User legacy = h.account("farai@innbucks.co.zw", "PRODUCT_OFFICER");
        legacy.setPhoneNumber("+263771112233");
        when(h.otps.deleteByPhoneNumber(anyString())).thenReturn(1);
        h.mvc.perform(post("/admin/staff/{id}/deactivate", legacy.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Left on 30 Sept.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
        verify(h.otps).deleteByPhoneNumber("farai@innbucks.co.zw");
        verify(h.otps).deleteByPhoneNumber("+263771112233");
    }

    @Test
    @DisplayName("an accepted staff account has no sign-in phone: only the email key is swept, and nothing NPEs")
    void nullPhone() throws Exception {
        User agent = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        agent.setPhoneNumber(null);
        h.mvc.perform(post("/admin/staff/{id}/deactivate", agent.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Left on 30 Sept.\"}"))
                .andExpect(status().isOk());
        verify(h.otps).deleteByPhoneNumber("tariro.moyo@innbucks.co.zw");
        verify(h.otps, never()).deleteByPhoneNumber(org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("PUT /admin/users/{id}/active=false shares the same revoker: both keys deleted")
    void putActiveFalse() throws Exception {
        User merchant = h.account("owner@shop.co.zw", "MERCHANT_ADMIN");
        merchant.setPhoneNumber("+263772223344");
        h.mvc.perform(put("/admin/users/{id}/active", merchant.getId()).principal(as(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        verify(h.otps).deleteByPhoneNumber("owner@shop.co.zw");
        verify(h.otps).deleteByPhoneNumber("+263772223344");
    }
}
