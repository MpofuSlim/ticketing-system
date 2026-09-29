package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/users/{id}/active} with {@code false} ends every session
 * the account holds, at once — driven end to end: a staff member signs in
 * (password + TOTP), an administrator deactivates them, and every credential
 * they hold is then refused.
 *
 * <p>Before this, deactivation flipped {@code active} and nothing else: the
 * access token kept working and {@code /auth/refresh}, which never read
 * {@code active}, minted fresh ones for the life of the refresh chain.
 *
 * <p>Runs against a real Redis too, so the cross-service contract is observed
 * rather than mocked: {@code auth:tokenver:<userUuid>} must hold the new
 * version once the deactivation has committed.
 */
class StaffDeactivationRevokesSessionsIT extends SessionRevocationItSupport {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @BeforeAll
    static void startRedis() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired StringRedisTemplate redis;

    @Test
    void deactivationEndsEverySession_atOnce() throws Exception {
        User staff = staff("PRODUCT_OFFICER", true);
        JsonNode session = signIn(staff.getEmail());
        String access = session.at("/token").asText();
        String refresh = session.at("/refreshToken").asText();

        // The session works before the deactivation.
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        long before = liveTokenVersion(staff.getId());
        assertThat(liveRefreshTokens(staff.getId())).isPositive();

        deactivate(staff.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deactivated"))
                .andExpect(jsonPath("$.data.active").value(false));

        // One atomic bump, and it reached the shared Redis after commit, under
        // the exact key every other service reads.
        long after = liveTokenVersion(staff.getId());
        assertThat(after).isEqualTo(before + 1);
        assertThat(redis.opsForValue().get(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + staff.getUserUuid()))
                .isEqualTo(Long.toString(after));
        assertThat(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX).isEqualTo("auth:tokenver:");

        // The old access token is refused by user-service on its very next use.
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));

        // Every refresh family is gone, and a refresh — or an organization
        // switch, which rotates the same token — is a 401, not "reuse detected".
        assertThat(liveRefreshTokens(staff.getId())).isZero();
        mockMvc.perform(post("/auth/refresh")
                        .header("Authorization", "Bearer " + refresh)
                        .header("X-Device-Id", DEVICE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));
        mockMvc.perform(post("/auth/organization-context")
                        .header("Authorization", "Bearer " + refresh)
                        .header("X-Device-Id", DEVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("account_inactive"));

        // And the password step refuses them too.
        passwordStep(staff.getEmail()).andExpect(status().isBadRequest());
    }

    @Test
    void theDeactivationIsAuditedUnderTheAdminsFullAddress() throws Exception {
        // V42: the administrator's email is the audit actor, and this one is
        // longer than the old VARCHAR(64) — whose insert used to fail silently.
        assertThat(ADMIN_EMAIL.length()).isGreaterThan(64);
        User staff = staff("PRODUCT_OFFICER", true);

        deactivate(staff.getId()).andExpect(status().isOk());

        Number rows = (Number) em.createNativeQuery("SELECT count(*) FROM audit_events "
                        + "WHERE event_type = 'USER_DEACTIVATED' AND actor_id = :actor AND target_id = :target")
                .setParameter("actor", ADMIN_EMAIL)
                .setParameter("target", String.valueOf(staff.getId()))
                .getSingleResult();
        assertThat(rows.longValue()).isEqualTo(1L);
    }
}
