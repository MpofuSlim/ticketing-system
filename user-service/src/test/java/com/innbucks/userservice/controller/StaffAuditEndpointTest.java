package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.StaffAccountService;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/staff/{id}/audit} (V44): the account's own history — the
 * staff events plus the admin actions on the account — newest first, one
 * server-rendered sentence per row, and never an hmac.
 */
class StaffAuditEndpointTest {

    private final StaffDispatchHarness h = new StaffDispatchHarness();

    private static AuditEvent event(long id, String type, String outcome, String failure, String metadata,
                                    Instant at) {
        return AuditEvent.builder().id(id).occurredAt(at).eventType(type).actorId(OWNER)
                .actorType(AuditService.ACTOR_TYPE_USER).targetId("4812").targetType(AuditService.TARGET_TYPE_USER)
                .outcome(outcome).failureReason(failure).metadata(metadata)
                .rowHmac("c2VjcmV0LXJvdy1obWFj").chainHmac("c2VjcmV0LWNoYWluLWhtYWM=").build();
    }

    @Test
    @DisplayName("newest first, summaries rendered, the note surfaced, no hmac in the body")
    void history() throws Exception {
        User tariro = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        List<AuditEvent> rows = List.of(
                event(3, "STAFF_GRANT_REFUSED", "FAILURE", "staff_unverified",
                        "{\"roles\":[\"FRAUD_DESK\"]}", Instant.parse("2026-09-30T08:00:00Z")),
                event(2, "USER_ROLES_CHANGED", "SUCCESS", null,
                        "{\"previousRoles\":[\"CALL_CENTER_AGENT\"],\"newRoles\":[\"CALL_CENTER_AGENT\","
                                + "\"FRAUD_DESK\"]}", Instant.parse("2026-09-29T12:00:00Z")),
                event(1, "STAFF_INVITED", "SUCCESS", null,
                        "{\"roles\":[\"CALL_CENTER_AGENT\"],\"note\":\"Joins the Harare call-center team on 1 Oct "
                                + "(HR-2291).\"}", Instant.parse("2026-09-29T08:15:02Z")));
        when(h.auditEvents.findForTarget(eq(String.valueOf(tariro.getId())), eq(AuditService.TARGET_TYPE_USER),
                any(), any(Pageable.class))).thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), 3));

        h.mvc.perform(get("/admin/staff/{id}/audit", tariro.getId()).principal(as(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].type").value("STAFF_GRANT_REFUSED"))
                .andExpect(jsonPath("$.data.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.data.content[0].summary").value("Refused: staff unverified."))
                .andExpect(jsonPath("$.data.content[1].summary")
                        .value("Roles changed from CALL_CENTER_AGENT to CALL_CENTER_AGENT, FRAUD_DESK."))
                .andExpect(jsonPath("$.data.content[2].type").value("STAFF_INVITED"))
                .andExpect(jsonPath("$.data.content[2].actor").value(OWNER))
                .andExpect(jsonPath("$.data.content[2].summary")
                        .value("Created with role CALL_CENTER_AGENT and invited by email."))
                .andExpect(jsonPath("$.data.content[2].note")
                        .value("Joins the Harare call-center team on 1 Oct (HR-2291)."))
                .andExpect(content().string(not(containsString("Hmac"))))
                .andExpect(content().string(not(containsString("c2VjcmV0"))));
    }

    @Test
    @DisplayName("the query is scoped to target_type USER and the staff event list, and page size is capped")
    void queryShape() throws Exception {
        User tariro = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        when(h.auditEvents.findForTarget(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
        h.mvc.perform(get("/admin/staff/{id}/audit", tariro.getId()).param("size", "5000").principal(as(OWNER)))
                .andExpect(status().isOk());
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(h.auditEvents).findForTarget(eq(String.valueOf(tariro.getId())), eq(AuditService.TARGET_TYPE_USER),
                eq(StaffAccountService.AUDIT_TYPES), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(StaffAccountService.AUDIT_TYPES).contains("STAFF_GRANT_REFUSED", "STAFF_INVITE_REPLAYED",
                "USER_ROLES_CHANGED", "USER_TEMP_PASSWORD_RESET", "MFA_ADMIN_RESET", "AUTH_ACCOUNT_UNLOCKED");
    }

    @Test
    @DisplayName("404 staff_not_found for an account that is not staff")
    void notStaff() throws Exception {
        User customer = h.account("rudo@example.com", "CUSTOMER");
        h.mvc.perform(get("/admin/staff/{id}/audit", customer.getId()).principal(as(OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("staff_not_found"));
    }
}
