package com.innbucks.userservice.config;

import com.innbucks.userservice.security.AuthenticatedCaller;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Enables Spring Data JPA auditing so {@code @CreatedBy} / {@code @LastModifiedBy}
 * on entities are auto-stamped with the acting principal on every INSERT/UPDATE.
 *
 * <p><b>Who is the auditor.</b> The caller's stable {@code user_uuid} (read from
 * the JWT claim map that {@code JwtFilter} stashes on the {@code Authentication}
 * details, via {@link AuthenticatedCaller}), falling back to the JWT subject
 * (email, the principal name). Unauthenticated / anonymous / system writes return
 * empty so the actor column is left null — this matters here because many User
 * updates happen on PUBLIC flows (e.g. self-registration, login-time session
 * bumps) where there is no authenticated principal yet.
 *
 * <p>This is complementary to the tamper-evident {@code audit_events} log (which
 * records specific security actions): the per-row {@code created_by/updated_by}
 * answers "who last touched THIS entity", cheaply, for the admin-managed rows.
 *
 * <p>Timestamps stay on each entity's own {@code @PrePersist} / {@code @PreUpdate}
 * (UTC-pinned {@code LocalDateTime.now(ZoneOffset.UTC)}); this only adds the "by
 * whom", so Spring Data's JVM-default-zone date-time provider is never used.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || auth instanceof AnonymousAuthenticationToken) {
                return Optional.empty();
            }
            UUID uuid = AuthenticatedCaller.userUuid(auth);
            if (uuid != null) {
                return Optional.of(uuid.toString());
            }
            String name = auth.getName();
            return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
        };
    }
}
