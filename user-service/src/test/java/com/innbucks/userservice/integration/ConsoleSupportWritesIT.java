package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Foundry console support writes end to end (V45/V46): bound to the agent's
 * own lookup, refused on self and on staff, idempotent on the key, and SEALED on
 * the tamper-evident chain — real Postgres, real sessions.
 */
class ConsoleSupportWritesIT extends SupportItSupport {

    @Test
    @DisplayName("unlock: bound to the lookup, sealed on the chain as the agent, and idempotent on the key")
    void unlockBoundSealedIdempotent() throws Exception {
        User merchant = lockedMerchant();
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        String agent = session(agentAccount);
        String lookupId = lookupId(agent, merchant.getPhoneNumber());
        String key = UUID.randomUUID().toString();

        JsonNode done = data(write(agent, merchant.getId(), "unlock", lookupId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Account unlocked")));
        assertThat(done.at("/outcome").asText()).isEqualTo("SUCCESS");
        assertThat(done.at("/replayed").asBoolean()).isFalse();
        assertThat(done.at("/whatHappensNext").asText()).startsWith("The account can sign in again now.");
        assertThat(done.at("/account/lockedUntil").isNull()).isTrue();

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedLoginAttempts()).isZero();

        // Sealed on the chain: actor = the agent, target = the account, the customer key masked.
        String metadata = (String) single("SELECT metadata FROM audit_events WHERE event_type = "
                + "'SUPPORT_CONSOLE_ACCOUNT_UNLOCKED' AND actor_id = ?1 AND target_id = ?2 AND chain_hmac IS NOT NULL",
                agentAccount.getEmail(), String.valueOf(merchant.getId()));
        assertThat(metadata).contains(lookupId).contains(key).contains("msisdn:****")
                .doesNotContain(merchant.getPhoneNumber()).doesNotContain(merchant.getEmail());
        assertThat(count("SELECT count(*) FROM support_actions WHERE idempotency_key = CAST(?1 AS uuid) "
                + "AND outcome = 'SUCCESS'", key)).isEqualTo(1);

        // The same key again: the stored answer, nothing done or sealed twice.
        write(agent, merchant.getId(), "unlock", lookupId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_ACCOUNT_UNLOCKED' "
                + "AND target_id = ?1", String.valueOf(merchant.getId()))).isEqualTo(1);

        // The same key for a different action: refused.
        write(agent, merchant.getId(), "send-password-reset", lookupId, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("idempotency_key_reused"));
    }

    @Test
    @DisplayName("binding: no lookup 400, a colleague's lookup 409, an id outside the lookup 404, no key 400")
    void binding() throws Exception {
        User merchant = lockedMerchant();
        User stranger = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String colleague = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String mine = lookupId(agent, merchant.getPhoneNumber());
        String theirs = lookupId(colleague, merchant.getPhoneNumber());

        write(agent, merchant.getId(), "unlock", null, UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("lookup_required"));
        write(agent, merchant.getId(), "unlock", theirs, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("lookup_expired"));
        write(agent, stranger.getId(), "unlock", mine, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
        detail(agent, stranger.getId(), mine)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
        write(agent, merchant.getId(), "unlock", mine, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errorCode").value("idempotency_key_required"));
        detail(agent, merchant.getId(), mine)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(merchant.getId()));
        // Nothing above changed the stranger.
        assertThat(users.findById(stranger.getId()).orElseThrow().getLockedUntil()).isNotNull();
    }

    @Test
    @DisplayName("self-action is 403; a staff target needs a supervisor, and even a supervisor can't change staff")
    void selfAndStaffTargets() throws Exception {
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        User colleague = eligibleStaff("CALL_CENTER_AGENT", true);
        String agent = session(agentAccount);
        String supervisor = session(eligibleStaff("CALL_CENTER_SUPERVISOR", true));

        String own = lookupId(agent, agentAccount.getEmail());
        write(agent, agentAccount.getId(), "unlock", own, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("support_self_action"));

        String onColleague = lookupId(agent, colleague.getEmail());
        write(agent, colleague.getId(), "unlock", onColleague, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("staff_target_requires_supervisor"));

        String bySupervisor = lookupId(supervisor, colleague.getEmail());
        write(supervisor, colleague.getId(), "unlock", bySupervisor, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("console_staff_account"));

        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_ACTION_REFUSED' "
                + "AND failure_reason IN ('support_self_action','staff_target_requires_supervisor',"
                + "'console_staff_account') AND target_id IN (?1, ?2)",
                String.valueOf(agentAccount.getId()), String.valueOf(colleague.getId()))).isEqualTo(3);
    }

    @Test
    @DisplayName("mfa/reset: an agent is refused; a supervisor resets, ends every session, and the owners are emailed")
    void mfaResetIsSupervisorOnly() throws Exception {
        User merchant = lockedMerchant();
        User owner = users.save(User.builder().firstName("Chipo").lastName("Ncube")
                .email("owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).active(true).approved(true).build());
        var org = memberRepository.findByUserId(merchant.getId()).get(0).getOrganizationId();
        memberRepository.save(com.innbucks.userservice.entity.OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(org).userId(owner.getId())
                .role(com.innbucks.userservice.entity.OrganizationMember.Role.OWNER).build());

        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        write(agent, merchant.getId(), "mfa/reset", lookupId(agent, merchant.getEmail()), UUID.randomUUID().toString())
                .andExpect(status().isForbidden());

        long before = liveTokenVersion(merchant.getId());
        User supervisorAccount = eligibleStaff("CALL_CENTER_SUPERVISOR", true);
        String supervisor = session(supervisorAccount);
        JsonNode done = data(write(supervisor, merchant.getId(), "mfa/reset", lookupId(supervisor, merchant.getEmail()),
                UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertThat(done.at("/whatHappensNext").asText()).startsWith("Two-factor sign-in is off for this account");

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.isMfaEnabled()).isFalse();
        assertThat(after.getMfaSecret()).isNull();
        assertThat(liveTokenVersion(merchant.getId())).isGreaterThan(before);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_MFA_RESET' "
                + "AND actor_id = ?1 AND target_id = ?2", supervisorAccount.getEmail(),
                String.valueOf(merchant.getId()))).isEqualTo(1);
        verify(email, timeout(10_000)).sendEmail(eq(owner.getEmail().toLowerCase(java.util.Locale.ROOT)),
                eq("InnBucks Foundry security notice"), anyString(), anyString());
    }

    @Test
    @DisplayName("send-password-reset emails a code to the account's own address")
    void sendPasswordReset() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        JsonNode done = data(write(agent, merchant.getId(), "send-password-reset",
                lookupId(agent, merchant.getPhoneNumber()), UUID.randomUUID().toString())
                .andExpect(status().isOk()));
        assertThat(done.at("/whatHappensNext").asText()).contains(merchant.getEmail());
        verify(email, timeout(10_000)).sendEmail(eq(merchant.getEmail()), eq("Your InnBucks password reset code"),
                anyString(), anyString());
    }
}
