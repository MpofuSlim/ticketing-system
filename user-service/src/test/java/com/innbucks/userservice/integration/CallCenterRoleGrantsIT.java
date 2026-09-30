package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.PermissionResolver;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V43 + V46 on a fresh database: the three customer-support built-ins exist,
 * are built in, and resolve to exactly the permissions the design gives them
 * (device security from V43, the support-console codes from V46) — and a call-center agent's sign-in carries those, and only those,
 * in both the token and the response's {@code permissions}.
 */
class CallCenterRoleGrantsIT extends SessionRevocationItSupport {

    @Autowired PermissionResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwtUtil;

    @Test
    void theThreeRolesResolveToExactlyTheirGrants() {
        // V43's device-security grants, plus V46's customer-support console codes.
        assertThat(resolver.resolve(List.of("CALL_CENTER_AGENT")))
                .containsExactlyInAnyOrder("device-security:read", "device-security:manage",
                        "support-console:read", "support-console:manage");
        // The supervisor diverges in V46: the MFA reset and acting on staff targets.
        assertThat(resolver.resolve(List.of("CALL_CENTER_SUPERVISOR")))
                .containsExactlyInAnyOrder("device-security:read", "device-security:manage",
                        "support-console:read", "support-console:manage",
                        "support-console:mfa:reset", "support-staff-targets:manage");
        assertThat(resolver.resolve(List.of("FRAUD_DESK")))
                .containsExactlyInAnyOrder("device-security:read", "device-security:fraud");
        // The add-on composes with an agent role: the union.
        assertThat(resolver.resolve(List.of("CALL_CENTER_AGENT", "FRAUD_DESK")))
                .containsExactlyInAnyOrder("device-security:read", "device-security:manage", "device-security:fraud",
                        "support-console:read", "support-console:manage");
    }

    @Test
    void theyAreBuiltInRowsSeededByTheMigration() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, builtin, created_by, description FROM roles "
                        + "WHERE name IN ('CALL_CENTER_AGENT','CALL_CENTER_SUPERVISOR','FRAUD_DESK') ORDER BY name");
        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("builtin")).as("%s", row.get("name")).isEqualTo(true);
            assertThat(row.get("created_by")).isEqualTo("flyway:V43");
            assertThat((String) row.get("description")).isNotBlank();
        }
        // SUPER_ADMIN is never enumerated: it still holds '*' alone.
        assertThat(jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE role_name = 'SUPER_ADMIN'", String.class))
                .containsExactly("*");
    }

    @Test
    void anAgentSignsInWithMfaAndCarriesExactlyItsPermissions() throws Exception {
        User agent = staff("CALL_CENTER_AGENT", true);

        // signIn is password + TOTP: an agent is a system user, challenged like staff.
        JsonNode session = signIn(agent.getEmail());

        Set<String> expected = Set.of("device-security:read", "device-security:manage",
                "support-console:read", "support-console:manage");
        List<String> listed = objectMapper.convertValue(session.at("/permissions"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(jwtUtil.extractPermissions(session.at("/token").asText()))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void theRolePickerListsThem() throws Exception {
        JsonNode roles = data(mockMvc.perform(get("/admin/roles").with(authentication(
                new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null,
                        List.of(new SimpleGrantedAuthority("roles:read"))))))
                .andExpect(status().isOk()));
        List<String> names = roles.findValuesAsText("name");
        assertThat(names).contains("CALL_CENTER_AGENT", "CALL_CENTER_SUPERVISOR", "FRAUD_DESK");
        // The console assigns staff and business roles; CUSTOMER comes from the
        // super app and is kept by PUT /admin/users/{id}/roles, so it is not offered.
        assertThat(names).doesNotContain("CUSTOMER").contains("MERCHANT_ADMIN", "EVENT_ORGANIZER");
    }
}
