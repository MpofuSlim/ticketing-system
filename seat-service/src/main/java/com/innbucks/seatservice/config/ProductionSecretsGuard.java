package com.innbucks.seatservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to boot under the {@code prod} profile if any sensitive secret still
 * holds its {@code change-me-*} placeholder default. Only active under
 * {@code -Dspring.profiles.active=prod}.
 */
@Configuration
@Profile("prod")
@Slf4j
public class ProductionSecretsGuard {

    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret"
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
                    "Refusing to start under 'prod' profile: " + offenders +
                    " still contain the placeholder marker '" + PLACEHOLDER_MARKER +
                    "'. Override via env vars (JWT_SECRET) before booting in production."
            );
        }
        log.info("Production secrets check passed ({} keys verified)", SECRETS_TO_CHECK.size());
    }
}
