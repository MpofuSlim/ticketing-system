package com.innbucks.userservice.integration;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.TokenVersionPublisher;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A change to who can do what is REQUIRED-audited: when its {@code audit_events}
 * row cannot be written, the change is refused with {@code 503 audit_unavailable}
 * and NOTHING of it survives — not the roles, not the permissions, not the
 * {@code token_version} bump, and nothing in the shared Redis.
 *
 * <p>Proved against real Postgres transactions and a real Redis, with the audit
 * write failing IN THE DATABASE: a trigger on {@code audit_events} refuses the
 * one row under test, so the whole production path runs — the outer change, the
 * flush, the bulk bump, the REQUIRES_NEW audit write that Postgres rejects,
 * {@code AuditUnavailableException}, the outer rollback, and the after-commit
 * publish that therefore never happens. A mocked {@code AuditService} would
 * have skipped exactly the transaction boundaries this is about.
 *
 * <p>The trigger only fires for the target this test names, and is dropped
 * after every test, so no other IT sharing the database can meet it. Once it is
 * gone the identical request succeeds — the control that proves the refusal was
 * the audit and nothing else.
 */
class RequiredAuditRollsBackTheChangeIT extends SessionRevocationItSupport {

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
    @Autowired JdbcTemplate jdbc;

    private static UsernamePasswordAuthenticationToken platformOwner() {
        return new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null, List.of(
                new SimpleGrantedAuthority("users:roles:write"),
                new SimpleGrantedAuthority("roles:write"),
                new SimpleGrantedAuthority("roles:read")));
    }

    /** Makes Postgres refuse the {@code eventType} audit row for {@code targetId}, and only that. */
    private void failAuditFor(String eventType, String targetId) {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_required_audit() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.event_type = '" + eventType + "' AND NEW.target_id = '" + targetId + "' THEN "
                + "RAISE EXCEPTION 'simulated audit outage'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_required_audit ON audit_events");
        jdbc.execute("CREATE TRIGGER it_fail_required_audit BEFORE INSERT ON audit_events "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_required_audit()");
    }

    @AfterEach
    void dropTheTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_required_audit ON audit_events");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_required_audit()");
    }

    private Set<String> liveRoles(Long userId) {
        return new java.util.TreeSet<>(jdbc.queryForList(
                "SELECT role FROM user_roles WHERE user_id = ?", String.class, userId));
    }

    private Set<String> livePermissions(String role) {
        return new java.util.TreeSet<>(jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE role_name = ?", String.class, role));
    }

    private String shared(User u) {
        return redis.opsForValue().get(TokenVersionPublisher.SHARED_TOKEN_VERSION_PREFIX + u.getUserUuid());
    }

    private long auditRows(String eventType, String targetId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_type = ? AND target_id = ?",
                Long.class, eventType, targetId);
    }

    private ResultActions setRoles(Long userId, String... roles) throws Exception {
        return mockMvc.perform(put("/admin/users/{id}/roles", userId)
                .with(authentication(platformOwner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("roles", List.of(roles)))));
    }

    private ResultActions setPermissions(String role, String... codes) throws Exception {
        return mockMvc.perform(put("/admin/roles/{name}/permissions", role)
                .with(authentication(platformOwner()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of(codes)))));
    }

    private static void refusedAsUnrecorded(ResultActions result) throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("503 SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("We couldn't record this change, so it wasn't made. Try again."))
                .andExpect(jsonPath("$.data.errorCode").value("audit_unavailable"));
    }

    @Test
    void aRoleGrantWhoseAuditFails_isNotMade_andNothingIsPublished() throws Exception {
        // A staff-eligible account (V44): a staff role may only be added to one.
        User account = eligibleStaff("CALL_CENTER_AGENT", false);
        String target = String.valueOf(account.getId());
        long before = liveTokenVersion(account.getId());
        failAuditFor("USER_ROLES_CHANGED", target);

        refusedAsUnrecorded(setRoles(account.getId(), "CALL_CENTER_AGENT", "FRAUD_DESK"));

        assertThat(liveRoles(account.getId())).containsExactly("CALL_CENTER_AGENT");
        assertThat(liveTokenVersion(account.getId())).as("the bump rolled back with the grant").isEqualTo(before);
        assertThat(shared(account)).as("a rolled-back bump publishes nothing").isNull();
        assertThat(auditRows("USER_ROLES_CHANGED", target)).isZero();

        // Control: with the audit path healthy, the identical request is made.
        dropTheTrigger();
        setRoles(account.getId(), "CALL_CENTER_AGENT", "FRAUD_DESK").andExpect(status().isOk());
        assertThat(liveRoles(account.getId())).containsExactly("CALL_CENTER_AGENT", "FRAUD_DESK");
        assertThat(liveTokenVersion(account.getId())).isEqualTo(before + 1);
        assertThat(shared(account)).isEqualTo(Long.toString(before + 1));
        assertThat(auditRows("USER_ROLES_CHANGED", target)).isEqualTo(1L);
    }

    @Test
    void aPlatformRemovalWhoseAuditFails_keepsThePermission_andSignsNobodyOut() throws Exception {
        String role = "AUDIT_PROBE_" + unique().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "X");
        mockMvc.perform(post("/admin/roles").with(authentication(platformOwner()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", role, "description", "Audit probe",
                                "permissions", List.of("device-security:read", "device-security:manage")))))
                .andExpect(status().isCreated());
        User first = holder(role);
        User second = holder(role);
        long v1 = liveTokenVersion(first.getId());
        long v2 = liveTokenVersion(second.getId());
        failAuditFor("ROLE_PERMISSIONS_CHANGED", role);

        // device-security:manage is PLATFORM: removing it would sign both out.
        refusedAsUnrecorded(setPermissions(role, "device-security:read"));

        assertThat(livePermissions(role)).containsExactly("device-security:manage", "device-security:read");
        assertThat(liveTokenVersion(first.getId())).isEqualTo(v1);
        assertThat(liveTokenVersion(second.getId())).isEqualTo(v2);
        assertThat(shared(first)).isNull();
        assertThat(shared(second)).isNull();
        assertThat(auditRows("ROLE_PERMISSIONS_CHANGED", role)).isZero();

        // Control.
        dropTheTrigger();
        setPermissions(role, "device-security:read").andExpect(status().isOk());
        assertThat(livePermissions(role)).containsExactly("device-security:read");
        assertThat(liveTokenVersion(first.getId())).isEqualTo(v1 + 1);
        assertThat(shared(first)).isEqualTo(Long.toString(v1 + 1));
        assertThat(shared(second)).isEqualTo(Long.toString(v2 + 1));
    }

    private User holder(String role) {
        return users.save(User.builder()
                .firstName("Chipo").lastName("Ncube")
                .email("holder-" + unique() + "@innbucks.co.zw")
                .phoneNumber(phone())
                .password(passwordEncoder.encode(PASSWORD))
                .roles(new LinkedHashSet<>(Set.of(role)))
                .active(true).approved(true)
                .build());
    }
}
