package com.innbucks.userservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to boot under the {@code prod} profile if any sensitive secret still
 * holds its {@code change-me-*} placeholder default from {@code application.yaml}.
 * Only active under {@code -Dspring.profiles.active=prod}, so dev/test runs
 * keep working against the convenient placeholder values.
 */
@Configuration
@Profile("prod")
@Slf4j
public class ProductionSecretsGuard {

    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token"
    );

    private static final String PLACEHOLDER_MARKER = "change-me";

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyNoPlaceholderSecrets() {
        List<String> offenders = new ArrayList<>();
        for (String key : SECRETS_TO_CHECK) {
            String value = env.getProperty(key);
            if (value != null && value.contains(PLACEHOLDER_MARKER)) {
                offenders.add(key);
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under 'prod' profile: the following secrets " +
                    "still have placeholder defaults containing '" + PLACEHOLDER_MARKER +
                    "': " + offenders + ". Override them via env vars (JWT_SECRET, " +
                    "INTERNAL_API_TOKEN) before booting in production."
            );
        }
        log.info("Production secrets check passed ({} keys verified)", SECRETS_TO_CHECK.size());
    }
}
