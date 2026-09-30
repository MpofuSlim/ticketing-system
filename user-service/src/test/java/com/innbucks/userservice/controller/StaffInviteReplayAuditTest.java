package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /auth/staff-invite/inspect} and {@code /accept} (V44) through real
 * dispatch: one opaque 400 {@code invite_invalid} for every failure, a password
 * mismatch that does not burn the link, and a replay written as
 * {@code STAFF_INVITE_REPLAYED} naming the account.
 */
class StaffInviteReplayAuditTest {

    private static final String PASSWORD = "Correct-Horse-7-Battery";

    private StaffDispatchHarness h;
    private User tariro;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        h = new StaffDispatchHarness();
        h.mvc.perform(post("/admin/staff").principal(as(OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Tariro","lastName":"Moyo","email":"tariro.moyo@innbucks.co.zw",
                                 "phoneNumber":"+263771234567","country":"Zimbabwe","roles":["CALL_CENTER_AGENT"],
                                 "note":"HR-2291"}
                                """))
                .andExpect(status().isCreated());
        tariro = h.userRows.values().stream().filter(u -> "tariro.moyo@innbucks.co.zw".equals(u.getEmail()))
                .findFirst().orElseThrow();
        token = h.lastRawToken();
    }

    private ResultActions accept(String tok, String password, String confirm) throws Exception {
        return h.mvc.perform(post("/auth/staff-invite/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + tok + "\",\"newPassword\":\"" + password + "\",\"confirmPassword\":\""
                        + confirm + "\"}"));
    }

    private static void invalid(ResultActions r) throws Exception {
        r.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This invite link is no longer valid. Ask your administrator to send a new one."))
                .andExpect(jsonPath("$.data.errorCode").value("invite_invalid"));
    }

    @Test
    @DisplayName("inspect: who it is for, without using it up; no-store")
    void inspect() throws Exception {
        for (int i = 0; i < 2; i++) {
            h.mvc.perform(post("/auth/staff-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.firstName").value("Tariro"))
                    .andExpect(jsonPath("$.data.email").value("tariro.moyo@innbucks.co.zw"));
        }
        assertThat(h.inviteRows.values().iterator().next().getUsedAt()).isNull();
        invalid(h.mvc.perform(post("/auth/staff-invite/inspect").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"STI-not-a-real-token\"}")));
    }

    @Test
    @DisplayName("accept: a password mismatch is checked BEFORE the link is used, so a typo does not burn it")
    void mismatchDoesNotBurn() throws Exception {
        accept(token, PASSWORD, PASSWORD + "x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Passwords do not match"));
        assertThat(h.inviteRows.values().iterator().next().getUsedAt()).isNull();
        accept(token, PASSWORD, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("accept: password set, email proven, phone gone to contact, 2FA cleared, sessions ended, audited")
    void accepted() throws Exception {
        tariro.setPhoneNumber("+263771234567");
        tariro.setMfaEnabled(true);
        tariro.setMfaSecret("JBSWY3DPEHPK3PXP");
        accept(token, PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value(
                        "Your password is set. Sign in to finish setting up two-step verification."))
                .andExpect(jsonPath("$.data.email").value("tariro.moyo@innbucks.co.zw"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
        assertThat(tariro.getPassword()).isEqualTo("{x}" + PASSWORD);
        assertThat(tariro.getEmailVerifiedAt()).isNotNull();
        assertThat(tariro.getPhoneNumber()).isNull();
        assertThat(tariro.isMfaEnabled()).isFalse();
        assertThat(tariro.getMfaSecret()).isNull();
        assertThat(h.profileRows.get(tariro.getId()).getInviteAcceptedAt()).isNotNull();
        assertThat(h.profileRows.get(tariro.getId()).getContactPhone()).isEqualTo("+263771234567");
        assertThat(tariro.getTokenVersion()).isEqualTo(1L);
        verify(h.refreshTokens).revokeAllForUser(eq(tariro.getId()), any());
        verify(h.deviceTrust).clearTrustForUser(tariro.getId());
        verify(h.backupCodes).deleteAllForUser(tariro.getId());
        verify(h.audit).recordRequired(eq(AuditEventType.STAFF_INVITE_ACCEPTED), eq("tariro.moyo@innbucks.co.zw"),
                any(), eq(String.valueOf(tariro.getId())), eq("USER"), any(), any());
        // Now staff-eligible.
        assertThat(h.eligibility.ineligibility(tariro)).isEmpty();
    }

    @Test
    @DisplayName("a used link presented again: the same 400, and STAFF_INVITE_REPLAYED naming the account")
    void replay() throws Exception {
        accept(token, PASSWORD, PASSWORD).andExpect(status().isOk());
        invalid(accept(token, PASSWORD, PASSWORD));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_INVITE_REPLAYED), any(), any(),
                eq(String.valueOf(tariro.getId())), eq("USER"), eq("used"), any(), any());
    }

    @Test
    @DisplayName("expired, revoked and unknown links: the same opaque 400")
    void everyFailureIsOpaque() throws Exception {
        StaffInvite invite = h.inviteRows.values().iterator().next();
        invite.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        invalid(accept(token, PASSWORD, PASSWORD));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_INVITE_REPLAYED), any(), any(), any(), any(),
                eq("expired"), any(), any());

        invite.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        invite.setRevokedAt(LocalDateTime.now(ZoneOffset.UTC));
        invite.setRevokedReason(StaffInvite.REVOKED_SUPERSEDED);
        invalid(accept(token, PASSWORD, PASSWORD));

        invalid(accept("STI-" + "A".repeat(43), PASSWORD, PASSWORD));
        invalid(accept("not-even-close", PASSWORD, PASSWORD));
        assertThat(tariro.getEmailVerifiedAt()).isNull();
    }

    @Test
    @DisplayName("an account no longer accept-eligible: the same 400, and a STAFF_GRANT_REFUSED accept_ineligible row")
    void ineligible() throws Exception {
        h.mvc.perform(post("/admin/staff/{id}/deactivate", tariro.getId()).principal(as(OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Offer withdrawn\"}"))
                .andExpect(status().isOk());
        // Deactivation revoked the invite; revive the row to reach the eligibility check.
        StaffInvite invite = h.inviteRows.values().iterator().next();
        invite.setRevokedAt(null);
        invalid(accept(token, PASSWORD, PASSWORD));
        verify(h.audit).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED), any(), any(),
                eq(String.valueOf(tariro.getId())), eq("USER"), eq("accept_ineligible"), any(), any());
        verify(h.audit, never()).recordRequired(eq(AuditEventType.STAFF_INVITE_ACCEPTED), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("validation never echoes the body")
    void validation() throws Exception {
        String body = h.mvc.perform(post("/auth/staff-invite/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"short\",\"confirmPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.newPassword").value("Password must be between 8 and 72 characters"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(token).doesNotContain("short\"");
        String unreadable = h.mvc.perform(post("/auth/staff-invite/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": " + token + "}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(unreadable).doesNotContain(token);
    }
}
