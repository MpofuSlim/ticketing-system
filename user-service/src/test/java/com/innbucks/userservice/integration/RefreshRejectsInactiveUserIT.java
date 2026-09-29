package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh rotation reads {@code active} — including for an account deactivated
 * the OLD way, before this release: {@code active = false} with the token
 * version untouched and every refresh family still live. That is the state
 * every pre-1a deactivation left behind, so the refusal cannot rely on the
 * deactivation having revoked anything itself.
 */
class RefreshRejectsInactiveUserIT extends SessionRevocationItSupport {

    private void deactivateTheOldWay(Long userId) {
        tx().executeWithoutResult(status -> em
                .createNativeQuery("UPDATE users SET active = FALSE WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate());
    }

    @Test
    void aPreReleaseDeactivation_stillCannotRefresh_andLosesEveryFamily() throws Exception {
        User customer = customer();
        JsonNode session = data(passwordStep(customer.getEmail()).andExpect(status().isOk()));
        String access = session.at("/token").asText();
        String refresh = session.at("/refreshToken").asText();
        long version = liveTokenVersion(customer.getId());

        deactivateTheOldWay(customer.getId());
        assertThat(liveTokenVersion(customer.getId())).isEqualTo(version);
        assertThat(liveRefreshTokens(customer.getId())).isPositive();

        // The version still matches, yet user-service refuses the access token:
        // JwtFilter's per-request read now carries `active`.
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));

        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + refresh)
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));

        // The refusal revoked what the old deactivation left live — and that
        // revocation committed despite the 401.
        assertThat(liveRefreshTokens(customer.getId())).isZero();
        Number audited = (Number) em.createNativeQuery("SELECT count(*) FROM audit_events "
                        + "WHERE event_type = 'AUTH_REFRESH_ACCOUNT_INACTIVE' AND target_id = :subject")
                .setParameter("subject", customer.getEmail())
                .getSingleResult();
        assertThat(audited.longValue()).isEqualTo(1L);
        Number reuse = (Number) em.createNativeQuery("SELECT count(*) FROM audit_events "
                        + "WHERE event_type = 'AUTH_REFRESH_REUSE_DETECTED' AND target_id = :subject")
                .setParameter("subject", customer.getEmail())
                .getSingleResult();
        assertThat(reuse.longValue()).as("never reported as token theft").isZero();
    }
}
