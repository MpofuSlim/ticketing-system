package com.innbucks.userservice.config;

import com.innbucks.userservice.security.AuthDetailsKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link JpaAuditingConfig#auditorAware()} — the resolver that stamps
 * {@code @CreatedBy}/{@code @LastModifiedBy} on the audited entities. Pure JUnit
 * (no Spring context, no Docker); the bean is a lambda over
 * {@link SecurityContextHolder}.
 */
class JpaAuditingConfigTest {

    private final AuditorAware<String> auditor = new JpaAuditingConfig().auditorAware();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String email, UUID userUuid) {
        var token = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"));
        if (userUuid != null) {
            Map<String, Object> details = new HashMap<>();
            details.put(AuthDetailsKeys.USER_UUID, userUuid);
            token.setDetails(details);
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void prefersUserUuidWhenClaimPresent() {
        UUID uuid = UUID.fromString("42424242-4242-4242-4242-424242424242");
        authenticate("admin@innbucks.co.zw", uuid);
        assertThat(auditor.getCurrentAuditor()).contains(uuid.toString());
    }

    @Test
    void fallsBackToEmailWhenNoUuidClaim() {
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
