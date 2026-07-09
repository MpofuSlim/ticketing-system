package com.innbucks.seatservice.config;

import com.innbucks.seatservice.security.JwtAuthDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link JpaAuditingConfig#auditorAware()} — the resolver that stamps
 * {@code @CreatedBy}/{@code @LastModifiedBy}. Pure JUnit (no Spring context, no
 * Docker); the bean is a lambda over {@link SecurityContextHolder}.
 */
class JpaAuditingConfigTest {

    private final AuditorAware<String> auditor = new JpaAuditingConfig().auditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String email, UUID organizerUuid) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.createAuthorityList("ROLE_EVENT_ORGANIZER"));
        token.setDetails(new JwtAuthDetails(email, null, organizerUuid));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void prefersOrganizerUuidWhenPresent() {
        UUID org = UUID.fromString("5fc4c9d2-7b4f-4d12-a1c3-9e2f0bda1d3e");
        authenticate("organizer@innbucks.co.zw", org);
        assertThat(auditor.getCurrentAuditor()).contains(org.toString());
    }

    @Test
    void fallsBackToEmailWhenNoOrganizerUuid() {
        authenticate("admin@innbucks.co.zw", null);
        assertThat(auditor.getCurrentAuditor()).contains("admin@innbucks.co.zw");
    }

    @Test
    void emptyWhenUnauthenticated() {
        SecurityContextHolder.clearContext();
        assertThat(auditor.getCurrentAuditor()).isEmpty();
    }

    @Test
    void emptyForAnonymousToken() {
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(anon);
        assertThat(auditor.getCurrentAuditor()).isEmpty();
    }
}
