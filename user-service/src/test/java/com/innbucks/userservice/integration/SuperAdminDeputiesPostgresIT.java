package com.innbucks.userservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.testsupport.SessionRevocationItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deputy SUPER_ADMINs (V52) end to end against real Postgres: the booted
 * {@code DataInitializer} stamps the seeded admin as the platform owner through
 * the real native {@code markPlatformOwner}; the owner promotes an
 * invite-redeemed, MFA-enrolled staff account (row locks, the atomic
 * {@code token_version} bump, every refresh family revoked, the REQUIRED audit
 * row on the chain), the deputy's next sign-in mints the wildcard; a deputy
 * (this suite's SUPER_ADMIN administrator, which is NOT the owner) is refused;
 * and the owner demotes it again without deactivating it.
 */
class SuperAdminDeputiesPostgresIT extends SessionRevocationItSupport {

    private UsernamePasswordAuthenticationToken asSuperAdmin(String email) {
        return new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    private long auditRows(String type, Long targetId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM audit_events WHERE event_type = :type AND target_id = :target")
                .setParameter("type", type)
                .setParameter("target", String.valueOf(targetId))
                .getSingleResult()).longValue();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void theSeededAdminIsTheOneOwner_andTheOwnerPromotesAndDemotesADeputy() throws Exception {
        User owner = users.findByPlatformOwnerTrue().orElseThrow();
        assertThat(owner.hasRole(User.Role.SUPER_ADMIN)).isTrue();
        assertThat(users.findByEmail(ADMIN_EMAIL).orElseThrow().isPlatformOwner()).isFalse();

        User tariro = eligibleStaff("CALL_CENTER_SUPERVISOR", true);
        signIn(tariro.getEmail());
        long before = liveTokenVersion(tariro.getId());
        assertThat(liveRefreshTokens(tariro.getId())).isPositive();

        // A deputy SUPER_ADMIN — this suite's administrator holds the role but
        // not the flag — is refused and changes nothing.
        mockMvc.perform(post("/admin/super-admins/{id}", tariro.getId()).with(authentication(asSuperAdmin(ADMIN_EMAIL))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("platform_owner_only"));
        assertThat(liveTokenVersion(tariro.getId())).isEqualTo(before);

        // The owner promotes: SUPER_ADMIN added, the other role kept, signed out everywhere.
        mockMvc.perform(post("/admin/super-admins/{id}", tariro.getId()).with(authentication(asSuperAdmin(owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.superAdmin").value(true))
                .andExpect(jsonPath("$.data.platformOwner").value(false));
        User promoted = users.findById(tariro.getId()).orElseThrow();
        assertThat(promoted.getRoles()).containsExactlyInAnyOrder("CALL_CENTER_SUPERVISOR", "SUPER_ADMIN");
        assertThat(promoted.isActive()).isTrue();
        assertThat(promoted.isPlatformOwner()).isFalse();
        assertThat(liveTokenVersion(tariro.getId())).isEqualTo(before + 1);
        assertThat(liveRefreshTokens(tariro.getId())).isZero();
        assertThat(auditRows("SUPER_ADMIN_PROMOTED", tariro.getId())).isEqualTo(1);

        // The deputy's next sign-in carries the wildcard, expanded.
        JsonNode session = signIn(tariro.getEmail());
        assertThat(strings(session.at("/permissions"))).contains("staff:create", "users:roles:write");

        // The owner can never be demoted, not even by itself.
        mockMvc.perform(delete("/admin/super-admins/{id}", owner.getId()).with(authentication(asSuperAdmin(owner.getEmail()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.reason").value("platform_owner"));

        // The owner demotes the deputy: SUPER_ADMIN gone, still active, signed out again.
        long promotedVersion = liveTokenVersion(tariro.getId());
        mockMvc.perform(delete("/admin/super-admins/{id}", tariro.getId()).with(authentication(asSuperAdmin(owner.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.superAdmin").value(false));
        User demoted = users.findById(tariro.getId()).orElseThrow();
        assertThat(demoted.getRoles()).containsExactly("CALL_CENTER_SUPERVISOR");
        assertThat(demoted.isActive()).isTrue();
        assertThat(liveTokenVersion(tariro.getId())).isEqualTo(promotedVersion + 1);
        assertThat(liveRefreshTokens(tariro.getId())).isZero();
        assertThat(auditRows("SUPER_ADMIN_DEMOTED", tariro.getId())).isEqualTo(1);
        assertThat(users.findByPlatformOwnerTrue().orElseThrow().getId()).isEqualTo(owner.getId());
    }
}
