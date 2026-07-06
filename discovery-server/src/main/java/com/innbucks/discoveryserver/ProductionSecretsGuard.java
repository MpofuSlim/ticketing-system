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
 * <p>"Deployment" = any active profile other than {@code dev/test/it/local}
 * (e.g. {@code prod}, {@code uat}, {@code staging}). An empty profile set is
 * treated as non-deployment, so local runs and stray {@code java -jar} runs keep
 * working against the open (no-auth) default. This mirrors the
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
        boolean deployment = active.length > 0
                && Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Eureka password guard skipped (non-deployment profile: {})",
                    active.length == 0 ? "<none>" : Arrays.toString(active));
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
