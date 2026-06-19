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

    // user-service holds secrets that ALL default to change-me-* in
    // application.yaml and ALL must be overridden in prod:
    //   - jwt.secret                   (env JWT_SECRET)              -> HS256 signs every issued access + refresh token
    //   - innbucks.internal-api-token  (env INTERNAL_API_TOKEN)      -> talks to loyalty-service S2S endpoints
    //   - oradian.internal-token       (env ORADIAN_INTERNAL_TOKEN)  -> talks to Oradian middleware S2S endpoints
    //   - whatsapp.api-key             (env WHATSAPP_API_KEY)         -> x-api-key for the WhatsApp notification gateway
    //   - national-id.hmac-secret      (env NATIONAL_ID_HMAC_SECRET)  -> keyed hash protecting national IDs at rest
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token",
            "oradian.internal-token",
            "whatsapp.api-key",
            "national-id.hmac-secret"
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
                    "INTERNAL_API_TOKEN, ORADIAN_INTERNAL_TOKEN, WHATSAPP_API_KEY, " +
                    "NATIONAL_ID_HMAC_SECRET) before booting in production."
            );
        }
        log.info("Production secrets check passed ({} keys verified)", SECRETS_TO_CHECK.size());
    }
}
