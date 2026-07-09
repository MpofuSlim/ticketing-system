package com.innbucks.eventservice.config;

import com.innbucks.eventservice.security.AuthenticatedCaller;
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
 * on entities are auto-stamped with the acting principal on every INSERT/UPDATE,
 * with no per-service handwiring at the call sites.
 *
 * <p><b>Who is the auditor.</b> The caller's stable {@code user_uuid} (the same id
 * carried in {@code events.tenant_user_uuid} and {@code users.user_uuid} in
 * user-service), read from the JWT claim map {@link com.innbucks.eventservice.security.JwtFilter}
 * stashes on the {@code Authentication} details. When a token carries no uuid
 * claim we fall back to the JWT subject (email, the principal name); for an
 * unauthenticated / anonymous / system write we return empty so the column is
 * left null rather than stamped with a misleading value.
 *
 * <p><b>Timestamps are untouched.</b> {@code Event} already stamps
 * {@code createdAt} / {@code updatedAt} via its own {@code @PrePersist} /
 * {@code @PreUpdate} using {@code LocalDateTime.now(ZoneOffset.UTC)} (the repo's
 * UTC convention). We deliberately do NOT annotate those with
 * {@code @CreatedDate} / {@code @LastModifiedDate} — Spring Data's default
 * date-time provider uses the JVM-default zone, which would regress that UTC
 * pinning. This config adds only the "by whom".
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
