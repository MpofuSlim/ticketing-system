package com.innbucks.discoveryserver;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * Refuses to boot under a real DEPLOYMENT profile if the Eureka registry's HTTP
 * Basic password is blank. {@link SecurityConfig} only enforces auth under a
 * deployment profile, and Spring Security's default user silently accepts an
 * empty password — so without this guard a deployed registry could come up wide
 * open, letting anything that reaches :8761 register a rogue instance and hijack
 * {@code lb://} traffic (audit CR-2).
 *
 * <p>"Deployment" = an active-profile set containing NO {@code dev/test/it/local}
 * profile (e.g. {@code prod}, {@code uat}, {@code staging}). A02-M3: this now
 * includes an EMPTY profile set — a registry launched without
 * {@code SPRING_PROFILES_ACTIVE} is treated as a deployment and fails closed on a
 * blank password instead of coming up open. Local dev / a stray {@code java -jar}
 * must opt out explicitly with a dev/test/local profile. This mirrors the
 * {@code ProductionSecretsGuard} the data services use.
 */
@Configuration
public class ProductionSecretsGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretsGuard.class);

    // The registry's HTTP Basic password. SecurityConfig hands this to Spring
    // Security's default user under a deployment profile; application.yaml binds
    // it from ${EUREKA_PASSWORD:} (blank default). A blank value means the
    // registry would accept an empty password — refuse to start instead.
    private static final String PASSWORD_KEY = "spring.security.user.password";

    // A blank password is tolerated only under these (local dev + test) profiles.
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyEurekaPasswordSet() {
        String[] active = env.getActiveProfiles();
        // A02-M3: fail-CLOSED. Deployment = the active-profile set contains NO
        // non-deployment (dev/test/it/local) profile — which now INCLUDES the
        // EMPTY set. A registry launched without SPRING_PROFILES_ACTIVE is
        // treated as a deployment and must not come up with a blank password;
        // local dev / a stray `java -jar` must opt out explicitly with a
        // dev/test/local profile.
        boolean deployment = Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Eureka password guard skipped (non-deployment profile: {})", Arrays.toString(active));
            return;
        }

        String password = env.getProperty(PASSWORD_KEY);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Refusing to start under deployment profile " + Arrays.toString(active) +
                    ": the Eureka registry password (" + PASSWORD_KEY + ") is blank. " +
                    "Set EUREKA_PASSWORD before deploying."
            );
        }
        log.info("Eureka password guard passed for profile {}", Arrays.toString(active));
    }
}
