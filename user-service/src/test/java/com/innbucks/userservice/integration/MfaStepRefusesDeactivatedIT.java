package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MFA step is bound to the session epoch (T1/T26).
 *
 * <p>The mfaToken used to carry no {@code tokenVersion}, live five minutes, be
 * reusable across retries, and {@code completeLoginWithMfa} never read
 * {@code active}: an administrator could deactivate someone between their
 * password and their code, and the code would still mint a full session — at
 * whatever version the deactivation had just set, so it survived it. And a
 * token that had already completed a sign-in could complete another.
 */
class MfaStepRefusesDeactivatedIT extends SessionRevocationItSupport {

    @Test
    void deactivatedBetweenThePasswordStepAndTheCode_is401() throws Exception {
        User staff = staff("PRODUCT_OFFICER", true);
        String mfaToken = data(passwordStep(staff.getEmail()).andExpect(status().isOk()))
                .at("/mfaToken").asText();

        deactivate(staff.getId()).andExpect(status().isOk());

        mfaVerify(mfaToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));
        assertThat(liveRefreshTokens(staff.getId())).isZero();
    }

    @Test
    void anMfaTokenIsSpentBySuccess_andCannotMintASecondSession() throws Exception {
        User staff = staff("PRODUCT_OFFICER", true);
        String mfaToken = data(passwordStep(staff.getEmail()).andExpect(status().isOk()))
                .at("/mfaToken").asText();
        long atPasswordStep = liveTokenVersion(staff.getId());

        mfaVerify(mfaToken).andExpect(status().isOk());
        assertThat(liveTokenVersion(staff.getId()))
                .as("a successful verify bumps before minting — that is what spends the token")
                .isEqualTo(atPasswordStep + 1);

        mfaVerify(mfaToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mfaToken is invalid or expired"));
    }

    @Test
    void anyLaterBump_endsAPendingChallenge() throws Exception {
        // An admin MFA reset between the password step and the code is one of
        // the bumps that must end the challenge (a newer login, a role change
        // or a password reset do the same).
        User staff = staff("PRODUCT_OFFICER", true);
        String mfaToken = data(passwordStep(staff.getEmail()).andExpect(status().isOk()))
                .at("/mfaToken").asText();

        mockMvc.perform(post("/admin/users/{id}/mfa/reset", staff.getId())
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("note", "Lost phone, verified by callback."))))
                .andExpect(status().isOk());

        mfaVerify(mfaToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mfaToken is invalid or expired"));
    }

    @Test
    void anEnrolmentTokenAfterADeactivation_is401() throws Exception {
        User staff = staff("PRODUCT_OFFICER", false);
        String enrolToken = data(passwordStep(staff.getEmail()).andExpect(status().isOk()))
                .at("/mfaToken").asText();

        deactivate(staff.getId()).andExpect(status().isOk());

        mockMvc.perform(post("/auth/mfa/enroll/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaToken", enrolToken))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));
    }

    @Test
    void theAdminResetIsAuditedAsTheAdminActingOnTheUser() throws Exception {
        User staff = staff("PRODUCT_OFFICER", true);

        mockMvc.perform(post("/admin/users/{id}/mfa/reset", staff.getId()).with(authentication(admin())))
                .andExpect(status().isOk());

        Number rows = (Number) em.createNativeQuery("SELECT count(*) FROM audit_events "
                        + "WHERE event_type = 'MFA_ADMIN_RESET' AND actor_id = :actor AND target_id = :target")
                .setParameter("actor", ADMIN_EMAIL)
                .setParameter("target", String.valueOf(staff.getId()))
                .getSingleResult();
        assertThat(rows.longValue()).isEqualTo(1L);
    }
}
