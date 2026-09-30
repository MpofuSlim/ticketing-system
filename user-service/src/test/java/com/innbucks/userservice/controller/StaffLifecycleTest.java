package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.testsupport.StaffDispatchHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static com.innbucks.userservice.testsupport.StaffDispatchHarness.OWNER;
import static com.innbucks.userservice.testsupport.StaffDispatchHarness.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deactivate, reactivate and resend/adopt (V44): self, SUPER_ADMIN and
 * authority refusals; reactivation comes back INVITED with the old credentials
 * gone; adoption blocks for the stated reasons.
 */
class StaffLifecycleTest {

    private static final String NOTE = "{\"note\":\"Left the company on 30 Sept.\"}";

    private StaffDispatchHarness h;

    @BeforeEach
    void setUp() {
        h = new StaffDispatchHarness();
    }

    private ResultActions act(String caller, Long id, String action, String body) throws Exception {
        var req = post("/admin/staff/{id}/" + action, id).principal(as(caller));
        return h.mvc.perform(body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("deactivate: signs out everywhere, revokes live invites, audits; then 409 already_deactivated")
    void deactivate() throws Exception {
        User agent = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        h.inviteRows.put(1L, StaffInvite.builder().id(1L).userId(agent.getId()).tokenHash("h").sentToEmail("x")
                .createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).createdByEmail(OWNER)
                .expiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1)).build());
        act(OWNER, agent.getId(), "deactivate", NOTE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Staff account deactivated"))
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.data.whatHappensNext").value(
                        "First was signed out everywhere and can't sign in until reactivated."));
        assertThat(agent.isActive()).isFalse();
        assertThat(agent.getTokenVersion()).isEqualTo(4L);
        assertThat(h.inviteRows.get(1L).getRevokedReason()).isEqualTo(StaffInvite.REVOKED_DEACTIVATED);
        verify(h.refreshTokens).revokeAllForUser(eq(agent.getId()), any());
        verify(h.deviceTrust).clearTrustForUser(agent.getId());
        verify(h.audit).recordRequired(eq(AuditEventType.STAFF_DEACTIVATED), eq(OWNER), any(),
                eq(String.valueOf(agent.getId())), eq("USER"), any(), any());

        act(OWNER, agent.getId(), "deactivate", NOTE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("already_deactivated"));
    }

    @Test
    @DisplayName("deactivate: 400 note missing, 400 yourself, 403 super_admin, 403 exceeds_your_authority")
    void deactivateRefusals() throws Exception {
        User agent = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        act(OWNER, agent.getId(), "deactivate", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.note").value("note is required"));

        User owner = h.userRows.values().stream().filter(u -> OWNER.equals(u.getEmail())).findFirst().orElseThrow();
        act(OWNER, owner.getId(), "deactivate", NOTE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_manageable"))
                .andExpect(jsonPath("$.data.reason").value("super_admin"));

        User supervisor = h.eligibleStaff("lead@innbucks.co.zw", "CALL_CENTER_SUPERVISOR");
        User fraud = h.eligibleStaff("fraud@innbucks.co.zw", "FRAUD_DESK");
        act(supervisor.getEmail(), fraud.getId(), "deactivate", NOTE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can't change this account."))
                .andExpect(jsonPath("$.data.reason").value("exceeds_your_authority"));
        act(supervisor.getEmail(), supervisor.getId(), "deactivate", NOTE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't deactivate your own account."))
                .andExpect(jsonPath("$.data.errorCode").value("cannot_deactivate_self"));
        assertThat(fraud.isActive()).isTrue();
        assertThat(supervisor.isActive()).isTrue();
    }

    @Test
    @DisplayName("reactivate: back as INVITED — unusable password, 2FA cleared, email unproven, invites revoked")
    void reactivateComesBackInvited() throws Exception {
        User agent = h.eligibleStaff("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        agent.setMfaEnabled(true);
        agent.setMfaSecret("JBSWY3DPEHPK3PXP");
        agent.setPassword("{x}old-password");
        act(OWNER, agent.getId(), "reactivate", NOTE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("not_deactivated"));
        act(OWNER, agent.getId(), "deactivate", NOTE).andExpect(status().isOk());

        act(OWNER, agent.getId(), "reactivate", NOTE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Staff account reactivated"))
                .andExpect(jsonPath("$.data.status").value("INVITED"))
                .andExpect(jsonPath("$.data.emailVerified").value(false))
                .andExpect(jsonPath("$.data.whatHappensNext").value("Send a new invite so they can set a password."));
        assertThat(agent.isActive()).isTrue();
        assertThat(agent.getPassword()).startsWith("{x}!INVITE-");
        assertThat(agent.isMfaEnabled()).isFalse();
        assertThat(agent.getMfaSecret()).isNull();
        assertThat(agent.getEmailVerifiedAt()).isNull();
        assertThat(h.profileRows.get(agent.getId()).getInviteAcceptedAt()).isNull();
        verify(h.backupCodes).deleteAllForUser(agent.getId());
        verify(h.audit).recordRequired(eq(AuditEventType.STAFF_REACTIVATED), eq(OWNER), any(),
                eq(String.valueOf(agent.getId())), eq("USER"), any(), any());
        // No invite is sent automatically.
        assertThat(h.invitesRequested).isEmpty();
    }

    @Test
    @DisplayName("resend: a new invite supersedes the old; 409 once accepted; 409 while deactivated; 429 over quota")
    void resend() throws Exception {
        User invited = h.account("tariro.moyo@innbucks.co.zw", "CALL_CENTER_AGENT");
        h.profileRows.put(invited.getId(), com.innbucks.userservice.entity.StaffProfile.builder()
                .userId(invited.getId()).createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).build());
        act(OWNER, invited.getId(), "resend-invite", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Invite sent to tariro.moyo@innbucks.co.zw."))
                .andExpect(jsonPath("$.data.status").value("INVITED"));
        act(OWNER, invited.getId(), "resend-invite", "{\"note\":\"Bounced\"}").andExpect(status().isOk());
        assertThat(h.inviteRows.values()).hasSize(2);
        StaffInvite first = h.inviteRows.values().iterator().next();
        assertThat(first.getRevokedReason()).isEqualTo(StaffInvite.REVOKED_SUPERSEDED);
        verify(h.audit, org.mockito.Mockito.times(2)).recordRequired(eq(AuditEventType.STAFF_INVITE_RESENT),
                eq(OWNER), any(), eq(String.valueOf(invited.getId())), eq("USER"), any(), any());

        h.properties.setInviteResendLimit(2);
        act(OWNER, invited.getId(), "resend-invite", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("invite_resend_limited"));

        User accepted = h.eligibleStaff("farai@innbucks.co.zw", "CALL_CENTER_AGENT");
        act(OWNER, accepted.getId(), "resend-invite", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("invite_not_pending"))
                .andExpect(jsonPath("$.message").value(
                        "This account has no pending invite: it has already set its password."));

        accepted.setActive(false);
        act(OWNER, accepted.getId(), "resend-invite", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This account is deactivated. Reactivate the account first, then send a new invite."));
    }

    @Test
    @DisplayName("adopt: a legacy staff account becomes INVITED at once, its sessions end, an invite goes out")
    void adopt() throws Exception {
        User legacy = h.account("farai.chikwanha@innbucks.co.zw", "PRODUCT_OFFICER");
        legacy.setPhoneNumber("+263772000111");
        act(OWNER, legacy.getId(), "resend-invite", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVITED"));
        assertThat(h.profileRows.get(legacy.getId()).isAdopted()).isTrue();
        assertThat(h.profileRows.get(legacy.getId()).getInviteAcceptedAt()).isNull();
        assertThat(legacy.getTokenVersion()).isEqualTo(4L);
        verify(h.refreshTokens).revokeAllForUser(eq(legacy.getId()), any());
        assertThat(h.invitesRequested).hasSize(1);
        assertThat(h.eligibility.isInvitePending(legacy)).isTrue();
    }

    @Test
    @DisplayName("adopt: 409 adoption_blocked for business roles, an active organization, or an off-domain address")
    void adoptionBlocked() throws Exception {
        User mixed = h.account("mixed@innbucks.co.zw", "PRODUCT_OFFICER", "MERCHANT_ADMIN");
        act(OWNER, mixed.getId(), "resend-invite", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("adoption_blocked"))
                .andExpect(jsonPath("$.data.reason").value("holds_non_staff_roles"));

        User owner = h.account("owner.staff@innbucks.co.zw", "PRODUCT_OFFICER");
        h.organizationOf(owner, OrganizationMember.Role.OWNER);
        act(OWNER, owner.getId(), "resend-invite", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("organization_member"))
                .andExpect(jsonPath("$.message").value("This account can't be adopted yet: it still belongs to a "
                        + "business organization. Suspend that organization first."));

        User off = h.account("pm@gmail.com", "PRODUCT_MANAGER");
        act(OWNER, off.getId(), "resend-invite", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reason").value("off_domain"));
        assertThat(h.profileRows).isEmpty();
        verify(h.audit, org.mockito.Mockito.atLeast(3)).recordFailure(eq(AuditEventType.STAFF_GRANT_REFUSED),
                eq(OWNER), any(), any(), any(), eq("adoption_blocked"), any(), any());
        verify(h.invites, org.mockito.Mockito.never()).countByUserIdAndCreatedAtAfter(anyLong(), any());
    }
}
