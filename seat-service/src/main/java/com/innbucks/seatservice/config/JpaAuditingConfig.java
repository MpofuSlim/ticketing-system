package com.innbucks.seatservice.config;

import com.innbucks.seatservice.security.JwtAuthDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Enables Spring Data JPA auditing so {@code @CreatedBy} / {@code @LastModifiedBy}
 * on entities are auto-stamped with the acting principal on every INSERT/UPDATE.
 *
 * <p><b>Who is the auditor.</b> seat-service's {@link JwtAuthDetails} carries the
 * owning-organizer pointer ({@code organizerUuid}) but no generic user uuid, so
 * the auditor is that {@code organizerUuid} when present (the stable id the
 * seat-category ownership check already uses), falling back to the JWT subject
 * (email, the principal name). Unauthenticated / anonymous / system writes return
 * empty so the actor column is left null rather than stamped misleadingly.
 *
 * <p>Timestamps stay on each entity's own {@code @PrePersist} / {@code @PreUpdate}
 * (UTC-pinned via {@code LocalDateTime.now(ZoneOffset.UTC)}); this only adds the
 * "by whom", so Spring Data's JVM-default-zone date-time provider is never used.
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
            if (auth.getDetails() instanceof JwtAuthDetails d && d.organizerUuid() != null) {
                return Optional.of(d.organizerUuid().toString());
            }
            String name = auth.getName();
            return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
        };
    }
}
