package innbucks.paymentservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to boot under a real DEPLOYMENT profile when any sensitive secret
 * still carries its {@code change-me-*} placeholder default from
 * {@code application.yaml}, or when the HS256 signing key is too short.
 * Mirrors the guard in the other backend services — payment-service was the
 * outlier that could quietly boot with placeholders intact, defeating the
 * rest of the secret-handling story.
 *
 * <p>"Deployment" = an active-profile set containing NO {@code dev/test/it/local}
 * profile (e.g. {@code prod}, {@code uat}, {@code staging}). A02-M3: this now
 * includes an EMPTY profile set — a container launched without
 * {@code SPRING_PROFILES_ACTIVE} is treated as a deployment and fails closed on
 * placeholder secrets instead of silently booting on them. Local dev / a stray
 * {@code java -jar} must opt out explicitly with a dev/test/local profile.
 * Stronger than gating on {@code prod} alone: a staging box, or a manifest that
 * sets some non-prod deployment profile, is covered too.
 */
@Configuration
@Slf4j
public class ProductionSecretsGuard {

    // payment-service holds three distinct shared secrets:
    //   - innbucks.internal-api-token   (env INTERNAL_API_TOKEN)     -> talks to loyalty-service
    //   - oradian-middleware.internal-token (env ORADIAN_INTERNAL_TOKEN) -> talks to Oradian middleware
    //   - jwt.secret                    (env JWT_SECRET)             -> verifies bearer JWTs minted by user-service
    // All three must be set in prod. As more secrets land (Stripe API key
    // when real payments wire in, etc.) add them here and the deployment-boot
    // gate widens automatically.
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "innbucks.internal-api-token",
            "oradian-middleware.internal-token",
            "jwt.secret",
            "whatsapp.api-key",
            "audit.hmac-secret"
    );

    private static final String PLACEHOLDER_MARKER = "change-me";

    // Placeholder secrets are tolerated only under these (local dev + test) profiles.
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    // HS256 requires a >= 32-byte key (Keys.hmacShaKeyFor throws below that);
    // fail fast at boot rather than at the first token sign.
    private static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verifyNoPlaceholderSecrets() {
        String[] active = env.getActiveProfiles();
        // A02-M3: fail-CLOSED. Deployment = the active-profile set contains NO
        // non-deployment (dev/test/it/local) profile — which now INCLUDES the
        // EMPTY set. A container launched without SPRING_PROFILES_ACTIVE is
        // treated as a deployment and must not boot on change-me placeholders;
        // local dev / a stray `java -jar` must opt out explicitly with a
        // dev/test/local profile. Every @SpringBootTest already sets an explicit
        // test/it profile, so the suite is unaffected.
        boolean deployment = Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Secrets guard skipped (non-deployment profile: {})", Arrays.toString(active));
            return;
        }

        List<String> offenders = new ArrayList<>();
        for (String key : SECRETS_TO_CHECK) {
            String value = env.getProperty(key);
            if (value != null && value.contains(PLACEHOLDER_MARKER)) {
                offenders.add(key);
            }
        }
        String jwt = env.getProperty("jwt.secret");
        if (jwt != null && jwt.length() < MIN_JWT_SECRET_LENGTH && !offenders.contains("jwt.secret")) {
            offenders.add("jwt.secret (too short: needs >= " + MIN_JWT_SECRET_LENGTH + " chars)");
        }
        // A02: Redis holds session-revocation + rate-limit state. If this service
        // is configured against Redis but the password is blank under a
        // deployment profile, refuse to boot — require Redis AUTH. compose/k8s
        // already provide REDIS_PASSWORD; this makes a forgotten one fail fast
        // instead of silently running an unauthenticated Redis that an
        // in-cluster attacker could use to tamper with revocation state.
        String redisPassword = env.getProperty("spring.data.redis.password");
        if (redisPassword != null && redisPassword.isBlank()) {
            offenders.add("spring.data.redis.password (blank — Redis AUTH required under deployment)");
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under deployment profile " + Arrays.toString(active) +
                    ": these secrets still hold placeholder/weak values: " + offenders +
                    ". Override them via env vars (INTERNAL_API_TOKEN, ORADIAN_INTERNAL_TOKEN, " +
                    "JWT_SECRET, WHATSAPP_API_KEY) before deploying."
            );
        }
        log.info("Secrets guard passed for profile {} ({} keys verified)",
                Arrays.toString(active), SECRETS_TO_CHECK.size());
    }
}
