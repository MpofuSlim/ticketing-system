package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Autowired MfaBackupCodeRepository backupCodes;
    @Autowired DeviceRepository devices;

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
    @DisplayName("self-action is 403: the customer's number is the agent's own contact phone")
    void selfAction() throws Exception {
        User merchant = lockedMerchant();
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        var profile = staffProfiles.findById(agentAccount.getId()).orElseThrow();
        profile.setContactPhone(merchant.getPhoneNumber());
        staffProfiles.save(profile);
        String agent = session(agentAccount);

        write(agent, merchant.getId(), "unlock", lookupId(agent, merchant.getPhoneNumber()),
                UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("support_self_action"));
        assertThat(users.findById(merchant.getId()).orElseThrow().getLockedUntil()).isNotNull();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_ACTION_REFUSED' "
                + "AND failure_reason = 'support_self_action' AND target_id = ?1",
                String.valueOf(merchant.getId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("S1: a staff account is never a target — the agent's own, or a colleague's, is 404 target_not_found")
    void staffAccountsAreNeverTargets() throws Exception {
        User agentAccount = eligibleStaff("CALL_CENTER_AGENT", true);
        User colleague = eligibleStaff("CALL_CENTER_AGENT", true);
        String agent = session(agentAccount);
        String supervisor = session(eligibleStaff("CALL_CENTER_SUPERVISOR", true));

        write(agent, agentAccount.getId(), "unlock", lookupId(agent, agentAccount.getEmail()),
                UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
        write(supervisor, colleague.getId(), "mfa/reset", lookupId(supervisor, colleague.getEmail()),
                UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("target_not_found"));
        assertThat(users.findById(colleague.getId()).orElseThrow().getMfaSecret()).isNotNull();
    }

    @Test
    @DisplayName("S5: an account that becomes STAFF between the search and the write is refused — agent and supervisor alike")
    void accountBecomesStaffBetweenSearchAndWrite() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String supervisor = session(eligibleStaff("CALL_CENTER_SUPERVISOR", true));
        String agentLookup = lookupId(agent, merchant.getEmail());
        String supervisorLookup = lookupId(supervisor, merchant.getEmail());

        // Now a staff account (a NAMED staff role), after both lookups were taken.
        User promoted = users.findById(merchant.getId()).orElseThrow();
        promoted.getRoles().add("CALL_CENTER_AGENT");
        users.save(promoted);
        long version = liveTokenVersion(merchant.getId());

        write(agent, merchant.getId(), "unlock", agentLookup, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("staff_target_requires_supervisor"));
        write(supervisor, merchant.getId(), "mfa/reset", supervisorLookup, UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("console_staff_account"));
        detail(supervisor, merchant.getId(), supervisorLookup)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("console_staff_account"))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.getLockedUntil()).isNotNull();
        assertThat(after.isMfaEnabled()).isTrue();
        assertThat(after.getMfaSecret()).isNotNull();
        assertThat(liveTokenVersion(merchant.getId())).isEqualTo(version);
        assertThat(count("SELECT count(*) FROM support_actions WHERE target = ?1",
                String.valueOf(merchant.getId()))).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_ACTION_REFUSED' "
                + "AND failure_reason IN ('staff_target_requires_supervisor','console_staff_account') "
                + "AND target_id = ?1", String.valueOf(merchant.getId()))).isEqualTo(2);
    }

    @Test
    @DisplayName("S2: unlock on a deactivated account is 409 account_inactive — nothing changes")
    void deactivatedAccountIsRefused() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String lookup = lookupId(agent, merchant.getPhoneNumber());
        User off = users.findById(merchant.getId()).orElseThrow();
        off.setActive(false);
        users.save(off);

        write(agent, merchant.getId(), "unlock", lookup, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));
        assertThat(users.findById(merchant.getId()).orElseThrow().getLockedUntil()).isNotNull();
        assertThat(strings(data(detail(agent, merchant.getId(), lookup).andExpect(status().isOk())).at("/actions")))
                .isEmpty();
    }

    @Test
    @DisplayName("mfa/reset: an agent is refused; a supervisor wipes the second factor, ends every session, and tells the account and each OTHER owner about THEIR businesses")
    void mfaResetIsSupervisorOnly() throws Exception {
        User merchant = lockedMerchant();
        User owner = users.save(User.builder().firstName("Chipo").lastName("Ncube")
                .email("owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).active(true).approved(true).build());
        var org = memberRepository.findByUserId(merchant.getId()).get(0).getOrganizationId();
        String orgName = organizationRepository.findById(org).orElseThrow().getName();
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(org).userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        // A second business the merchant works in, owned by someone else.
        User otherOwner = users.save(User.builder().firstName("Farai").lastName("Sibanda")
                .email("other-owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).active(true).approved(true).build());
        String otherName = "Sibanda Hardware " + unique();
        var other = organizationRepository.save(com.innbucks.userservice.entity.Organization.builder()
                .id(UUID.randomUUID()).name(otherName).createdByUserId(otherOwner.getId()).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(other.getId())
                .userId(otherOwner.getId()).role(OrganizationMember.Role.OWNER).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID()).organizationId(other.getId())
                .userId(merchant.getId()).role(OrganizationMember.Role.STAFF).build());
        // What the wipe must clear: backup codes and a trusted browser.
        backupCodes.save(com.innbucks.userservice.entity.MfaBackupCode.builder().userId(merchant.getId())
                .codeHash("hash-" + unique()).createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).build());
        devices.save(com.innbucks.userservice.entity.Device.builder().user(merchant).deviceId("browser-" + unique())
                .registeredAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))
                .mfaTrustTokenHash("trust-" + unique())
                .mfaTrustedUntil(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(20)).build());

        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        write(agent, merchant.getId(), "mfa/reset", lookupId(agent, merchant.getEmail()), UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
        // T5: the refused agent changed nothing.
        User untouched = users.findById(merchant.getId()).orElseThrow();
        assertThat(untouched.isMfaEnabled()).isTrue();
        assertThat(untouched.getMfaSecret()).isEqualTo(TOTP_SECRET);

        long before = liveTokenVersion(merchant.getId());
        User supervisorAccount = eligibleStaff("CALL_CENTER_SUPERVISOR", true);
        String supervisor = session(supervisorAccount);
        JsonNode done = data(write(supervisor, merchant.getId(), "mfa/reset", lookupId(supervisor, merchant.getEmail()),
                UUID.randomUUID().toString()).andExpect(status().isOk()));
        assertThat(done.at("/whatHappensNext").asText()).startsWith("Two-factor sign-in is off for this account")
                .endsWith("emailed the other owners of its businesses.");
        assertThat(strings(done.at("/account/actions"))).doesNotContain("mfa/reset");

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.isMfaEnabled()).isFalse();
        assertThat(after.getMfaSecret()).isNull();
        assertThat(liveTokenVersion(merchant.getId())).isGreaterThan(before);
        assertThat(count("SELECT count(*) FROM mfa_backup_codes WHERE user_id = ?1", merchant.getId())).isZero();
        assertThat(count("SELECT count(*) FROM devices WHERE user_id = ?1 AND (mfa_trust_token_hash IS NOT NULL "
                + "OR mfa_trusted_until IS NOT NULL)", merchant.getId())).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_MFA_RESET' "
                + "AND actor_id = ?1 AND target_id = ?2", supervisorAccount.getEmail(),
                String.valueOf(merchant.getId()))).isEqualTo(1);

        // The account's own MFA_DISABLED security alert.
        verify(email, timeout(10_000)).sendEmail(eq(merchant.getEmail()), eq("InnBucks Foundry account security alert"),
                contains("turned OFF"), eq("SECURITY-MFA_DISABLED-" + merchant.getId()));
        // Each other owner, about THEIR business only.
        ArgumentCaptor<String> ownerBody = ArgumentCaptor.forClass(String.class);
        verify(email, timeout(10_000)).sendEmail(eq(owner.getEmail().toLowerCase(java.util.Locale.ROOT)),
                eq("InnBucks Foundry security notice"), ownerBody.capture(), anyString());
        assertThat(ownerBody.getValue()).contains(orgName).doesNotContain(otherName);
        ArgumentCaptor<String> otherBody = ArgumentCaptor.forClass(String.class);
        verify(email, timeout(10_000)).sendEmail(eq(otherOwner.getEmail().toLowerCase(java.util.Locale.ROOT)),
                eq("InnBucks Foundry security notice"), otherBody.capture(), anyString());
        assertThat(otherBody.getValue()).contains(otherName).doesNotContain(orgName);
    }

    @Test
    @DisplayName("send-password-reset emails a code to the account's own address, after the action is sealed")
    void sendPasswordReset() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        JsonNode done = data(write(agent, merchant.getId(), "send-password-reset",
                lookupId(agent, merchant.getPhoneNumber()), UUID.randomUUID().toString())
                .andExpect(status().isOk()));
        assertThat(done.at("/whatHappensNext").asText()).startsWith("We're emailing a password-reset code to "
                + merchant.getEmail() + " now.");
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_PASSWORD_RESET_SENT' "
                + "AND target_id = ?1", String.valueOf(merchant.getId()))).isEqualTo(1);
        verify(email, timeout(10_000)).sendEmail(eq(merchant.getEmail()), eq("Your InnBucks password reset code"),
                anyString(), anyString());
        verify(email, never()).sendEmail(eq(merchant.getPhoneNumber()), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("T7: the same Idempotency-Key twice AT ONCE — one acts, the other is answered from it; one seal")
    void concurrentRepeatActsOnce() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String lookup = lookupId(agent, merchant.getPhoneNumber());
        String key = UUID.randomUUID().toString();

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<JsonNode>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return data(write(agent, merchant.getId(), "unlock", lookup, key).andExpect(status().isOk()));
                }));
            }
            go.countDown();
            java.util.List<Boolean> replayed = new java.util.ArrayList<>();
            for (var f : results) {
                JsonNode r = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(r.at("/outcome").asText()).isEqualTo("SUCCESS");
                replayed.add(r.at("/replayed").asBoolean());
            }
            assertThat(replayed).containsExactlyInAnyOrder(false, true);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM support_actions WHERE idempotency_key = CAST(?1 AS uuid)", key))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_ACCOUNT_UNLOCKED' "
                + "AND target_id = ?1", String.valueOf(merchant.getId()))).isEqualTo(1);
    }
}
