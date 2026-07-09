package com.innbucks.eventservice.config;

import com.innbucks.eventservice.security.AuthDetailsKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link JpaAuditingConfig#auditorAware()} resolution used to stamp
 * {@code @CreatedBy}/{@code @LastModifiedBy}. Pure JUnit — no Spring context, no
 * Docker/Testcontainers — so it runs anywhere; the bean is a plain lambda over
 * the {@link SecurityContextHolder}.
 */
class JpaAuditingConfigTest {

    private final AuditorAware<String> auditor = new JpaAuditingConfig().auditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String email, UUID userUuid) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.createAuthorityList("ROLE_EVENT_ORGANIZER"));
        if (userUuid != null) {
            Map<String, Object> details = new HashMap<>();
            details.put(AuthDetailsKeys.USER_UUID, userUuid);
            token.setDetails(details);
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void prefersUserUuidWhenClaimPresent() {
        UUID uuid = UUID.fromString("8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e");
        authenticate("organizer@innbucks.co.zw", uuid);
        assertThat(auditor.getCurrentAuditor()).contains(uuid.toString());
    }

    @Test
    void fallsBackToEmailWhenNoUuidClaim() {
        authenticate("organizer@innbucks.co.zw", null);
        assertThat(auditor.getCurrentAuditor()).contains("organizer@innbucks.co.zw");
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
