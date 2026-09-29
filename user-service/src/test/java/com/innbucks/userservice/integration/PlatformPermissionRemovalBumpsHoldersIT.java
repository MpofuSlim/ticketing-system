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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /admin/roles/{name}/permissions} removing a PLATFORM code signs
 * every holder of the role out at once: ONE atomic {@code token_version} bump
 * across the holders, each new version in the shared Redis after commit, and
 * the holder's old access token refused on its next use. Removing only a TENANT
 * code does not: the change reaches holders at their next refresh.
 *
 * <p>Driven end to end over real Postgres and Redis: an administrator composes
 * a role, two accounts hold it, one signs in, and the permission edits follow.
 */
class PlatformPermissionRemovalBumpsHoldersIT extends SessionRevocationItSupport {

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

    private static UsernamePasswordAuthenticationToken roleAdmin() {
        return new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null,
                List.of(new SimpleGrantedAuthority("roles:write"), new SimpleGrantedAuthority("roles:read")));
    }

    private ResultActions setPermissions(String role, String... codes) throws Exception {
        return mockMvc.perform(put("/admin/roles/{name}/permissions", role)
                .with(authentication(roleAdmin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("permissions", List.of(codes)))));
    }

    private User holder(String role, boolean enrolled) {
        return users.save(User.builder()
                .firstName("Chipo").lastName("Ncube")
                .email("holder-" + unique() + "@innbucks.co.zw")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(new LinkedHashSet<>(Set.of(role)))
                .active(true).approved(true)
                .mfaEnabled(enrolled)
                .mfaSecret(enrolled ? TOTP_SECRET : null)
                .build());
    }

    private String shared(User u) {
        return redis.opsForValue().get(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + u.getUserUuid());
    }

    @Test
    void removingAPlatformCodeSignsEveryHolderOut_aTenantCodeDoesNot() throws Exception {
        String role = "PHONE_SUPPORT_" + unique().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "X");
        mockMvc.perform(post("/admin/roles").with(authentication(roleAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", role, "description", "Phone support",
                                "permissions", List.of("device-security:read", "device-security:manage",
                                        "team-members:read")))))
                .andExpect(status().isCreated());

        User signedIn = holder(role, true);
        User other = holder(role, false);
        User bystander = staff("PRODUCT_OFFICER", false);
        JsonNode session = signIn(signedIn.getEmail());
        String access = session.at("/token").asText();
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        long v1 = liveTokenVersion(signedIn.getId());
        long v2 = liveTokenVersion(other.getId());
        long vb = liveTokenVersion(bystander.getId());

        // TENANT removal: nobody is signed out.
        setPermissions(role, "device-security:read", "device-security:manage").andExpect(status().isOk());
        assertThat(liveTokenVersion(signedIn.getId())).isEqualTo(v1);
        assertThat(liveTokenVersion(other.getId())).isEqualTo(v2);
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        // PLATFORM removal: every holder bumped once, published after commit.
        setPermissions(role, "device-security:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[0]").value("device-security:read"));
        assertThat(liveTokenVersion(signedIn.getId())).isEqualTo(v1 + 1);
        assertThat(liveTokenVersion(other.getId())).isEqualTo(v2 + 1);
        assertThat(liveTokenVersion(bystander.getId())).as("a non-holder is untouched").isEqualTo(vb);
        assertThat(shared(signedIn)).isEqualTo(Long.toString(v1 + 1));
        assertThat(shared(other)).isEqualTo(Long.toString(v2 + 1));

        // The signed-in holder's old token is refused at once.
        mockMvc.perform(get("/notifications/unread-count").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_SUPERSEDED"));

        // And the audit row says who changed what, and how many were signed out.
        Object metadata = em.createNativeQuery("SELECT metadata FROM audit_events "
                        + "WHERE event_type = 'ROLE_PERMISSIONS_CHANGED' AND target_id = :role "
                        + "ORDER BY id DESC LIMIT 1")
                .setParameter("role", role)
                .getSingleResult();
        JsonNode detail = objectMapper.readTree(String.valueOf(metadata));
        assertThat(detail.at("/platformPermissionRemoved").asBoolean()).isTrue();
        assertThat(detail.at("/holdersSignedOut").asInt()).isEqualTo(2);
    }
}
