package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SupportItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1: a console support write whose SEAL cannot be written is not made — and
 * nothing it would have caused happens. Proved with the audit write failing IN
 * THE DATABASE (a trigger on {@code audit_events} refusing the one row under
 * test, as {@code RequiredAuditRollsBackTheChangeIT} does), so the real
 * transaction boundaries run: the account row lock, the support_actions row, the
 * change, the REQUIRES_NEW seal Postgres rejects, the outer rollback — and the
 * after-commit sends (the reset email, the owners' notice, the account's alert)
 * that therefore never fire.
 *
 * <p>The trigger names one target and is dropped after every test; once it is
 * gone the identical request succeeds — the control proving the refusal was the
 * seal and nothing else.
 */
class SupportWriteSealFailureIT extends SupportItSupport {

    @Autowired JdbcTemplate jdbc;

    private void failSealFor(Long targetId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_support_seal() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.event_type LIKE 'SUPPORT_CONSOLE_%' AND NEW.target_id = '" + targetId + "' THEN "
                + "RAISE EXCEPTION 'simulated audit outage'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_support_seal ON audit_events");
        jdbc.execute("CREATE TRIGGER it_fail_support_seal BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_support_seal()");
    }

    @AfterEach
    void dropTheTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_support_seal ON audit_events");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_support_seal()");
    }

    private static void refusedAsUnsealed(ResultActions result) throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"))
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."));
    }

    private long supportActions(Long target) {
        return count("SELECT count(*) FROM support_actions WHERE target = ?1", String.valueOf(target));
    }

    @Test
    @DisplayName("unlock: 503, still locked, no support_actions row, no seal — and the same request succeeds once the seal can be written")
    void unlockWhoseSealFailsIsNotMade() throws Exception {
        User merchant = lockedMerchant();
        Instant lockedUntil = users.findById(merchant.getId()).orElseThrow().getLockedUntil();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String lookup = lookupId(agent, merchant.getPhoneNumber());
        failSealFor(merchant.getId());

        refusedAsUnsealed(write(agent, merchant.getId(), "unlock", lookup, UUID.randomUUID().toString()));

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.getLockedUntil()).isEqualTo(lockedUntil);
        assertThat(after.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(supportActions(merchant.getId())).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_type = 'SUPPORT_CONSOLE_ACCOUNT_UNLOCKED' "
                + "AND target_id = ?1", String.valueOf(merchant.getId()))).isZero();

        dropTheTrigger();
        JsonNode done = data(write(agent, merchant.getId(), "unlock", lookup, UUID.randomUUID().toString())
                .andExpect(status().isOk()));
        assertThat(done.at("/account/lockedUntil").isNull()).isTrue();
        assertThat(supportActions(merchant.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("mfa/reset: 503, the second factor and token_version untouched, no row, and NO owner notice or account alert")
    void mfaResetWhoseSealFailsIsNotMade() throws Exception {
        User merchant = lockedMerchant();
        User owner = users.save(User.builder().firstName("Chipo").lastName("Ncube")
                .email("owner-" + unique() + "@example.com").phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(User.roleNames(User.Role.MERCHANT_ADMIN)).active(true).approved(true).build());
        memberRepository.save(OrganizationMember.builder().id(UUID.randomUUID())
                .organizationId(memberRepository.findByUserId(merchant.getId()).get(0).getOrganizationId())
                .userId(owner.getId()).role(OrganizationMember.Role.OWNER).build());
        String supervisor = session(eligibleStaff("CALL_CENTER_SUPERVISOR", true));
        String lookup = lookupId(supervisor, merchant.getEmail());
        long version = liveTokenVersion(merchant.getId());
        failSealFor(merchant.getId());

        refusedAsUnsealed(write(supervisor, merchant.getId(), "mfa/reset", lookup, UUID.randomUUID().toString()));

        User after = users.findById(merchant.getId()).orElseThrow();
        assertThat(after.isMfaEnabled()).isTrue();
        assertThat(after.getMfaSecret()).isEqualTo(TOTP_SECRET);
        assertThat(liveTokenVersion(merchant.getId())).isEqualTo(version);
        assertThat(supportActions(merchant.getId())).isZero();
        // The account's alert and the owners' notice are AFTER_COMMIT: a rolled-back reset announces nothing.
        verify(email, after(2_000).never()).sendEmail(eq(owner.getEmail()), anyString(), anyString(), anyString());
        verify(email, never()).sendEmail(eq(merchant.getEmail()), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("send-password-reset: 503 and NO email — the code is sent only after the action is sealed")
    void resetWhoseSealFailsSendsNothing() throws Exception {
        User merchant = lockedMerchant();
        String agent = session(eligibleStaff("CALL_CENTER_AGENT", true));
        String lookup = lookupId(agent, merchant.getPhoneNumber());
        failSealFor(merchant.getId());

        refusedAsUnsealed(write(agent, merchant.getId(), "send-password-reset", lookup, UUID.randomUUID().toString()));

        verify(email, after(2_000).never()).sendEmail(eq(merchant.getEmail()), anyString(), anyString(), anyString());
        assertThat(supportActions(merchant.getId())).isZero();
        // No live code was left behind either: the OTP row rolled back with the action.
        assertThat(count("SELECT count(*) FROM otps WHERE phone_number = ?1", merchant.getEmail())).isZero();

        dropTheTrigger();
        write(agent, merchant.getId(), "send-password-reset", lookup, UUID.randomUUID().toString())
                .andExpect(status().isOk());
        verify(email, timeout(10_000)).sendEmail(eq(merchant.getEmail()), eq("Your InnBucks password reset code"),
                anyString(), anyString());
    }
}
