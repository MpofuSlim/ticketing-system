package innbucks.paymentservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to boot under the {@code prod} profile when any sensitive secret
 * still carries its {@code change-me-*} placeholder default from
 * {@code application.yaml}. Mirrors the guard in the other backend services
 * — payment-service was the outlier that could quietly boot in prod with
 * placeholders intact, defeating the rest of the secret-handling story.
 *
 * <p>Active only under {@code -Dspring.profiles.active=prod}, so dev/CI runs
 * against the convenient placeholder values keep working.
 */
@Configuration
@Profile("prod")
@Slf4j
public class ProductionSecretsGuard {

    // Today payment-service only needs the shared internal token. As more
    // secrets land (Stripe API key when real payments wire in, etc.) add
    // them here and the prod-boot gate widens automatically.
    private static final List<String> SECRETS_TO_CHECK = List.of(
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
                    "': " + offenders + ". Override them via env vars (INTERNAL_API_TOKEN) " +
                    "before booting in production."
            );
        }
        log.info("ProductionSecretsGuard verified {} secret(s); none carry placeholder defaults.",
                SECRETS_TO_CHECK.size());
    }
}
