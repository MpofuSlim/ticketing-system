package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The staff directory (V44) shows SUPER_ADMIN rows — never manageable — only to
 * a caller holding the wildcard. Anyone else holding {@code staff:read} sees
 * none, and {@code GET /admin/staff/{id}} on a SUPER_ADMIN is the same 404 as a
 * non-staff id, so the directory is no oracle for the platform owners.
 */
class StaffDirectoryHidesSuperAdminTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    /**
     * {@code staff:read} is not assignable through the API (reserved to the
     * wildcard), so this seeds the role row directly — the shape a future
     * reviewed migration could produce, which is exactly the case the filter
     * exists for.
     */
    private User auditor() {
        h.role("STAFF_AUDITOR", "staff:read", "users:read");
        return h.eligibleStaff("auditor@innbucks.co.zw", "STAFF_AUDITOR");
    }

    @Test
    @DisplayName("a wildcard caller sees SUPER_ADMIN rows, marked manageable=false")
    void wildcardSeesThem() throws Exception {
        h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        h.mvc.perform(get("/admin/staff").principal(as(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].email", hasItem(OWNER)))
                .andExpect(jsonPath("$.data.content[?(@.email == '" + OWNER + "')].manageable").value(false))
                .andExpect(jsonPath("$.data.content[?(@.email == 'tariro.moyo@innbucks.co.zw')].manageable")
                        .value(true));
    }

    @Test
    @DisplayName("a non-wildcard staff:read holder sees no SUPER_ADMIN row, and GET /{id} on one is 404")
    void othersDoNot() throws Exception {
        User auditor = auditor();
        User secondOwner = h.account("second.owner@innbucks.co.zw", "SUPER_ADMIN");
        h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");

        h.mvc.perform(get("/admin/staff").principal(as(auditor.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].email", not(hasItem(OWNER))))
                .andExpect(jsonPath("$.data.content[*].email", not(hasItem(secondOwner.getEmail()))))
                .andExpect(jsonPath("$.data.content[*].email", hasItem("tariro.moyo@innbucks.co.zw")));

        h.mvc.perform(get("/admin/staff").param("role", "SUPER_ADMIN").principal(as(auditor.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        h.mvc.perform(get("/admin/staff/{id}", secondOwner.getId()).principal(as(auditor.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Staff account not found."))
                .andExpect(jsonPath("$.data.errorCode").value("staff_not_found"));
        h.mvc.perform(get("/admin/staff/{id}/audit", secondOwner.getId()).principal(as(auditor.getEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("staff_not_found"));
    }
}
