package com.innbucks.userservice.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Refuses to boot under a real DEPLOYMENT profile if any sensitive secret still
 * holds its {@code change-me-*} placeholder default from {@code application.yaml},
 * or if the HS256 signing key is too short.
 *
 * <p>"Deployment" = any active profile other than {@code dev/test/it/local}
 * (e.g. {@code prod}, {@code uat}, {@code staging}). An empty profile set is
 * treated as non-deployment, so no-profile test contexts and stray
 * {@code java -jar} runs keep working against the convenient placeholders. This
 * is stronger than gating on {@code prod} alone: a staging box, or a manifest
 * that sets some non-prod deployment profile, is now covered too.
 */
@Configuration
@Slf4j
public class ProductionSecretsGuard {

    // user-service holds secrets that ALL default to change-me-* in
    // application.yaml and ALL must be overridden in a real deployment:
    //   - jwt.secret                   (env JWT_SECRET)              -> HS256 signs every issued access + refresh token
    //   - innbucks.internal-api-token  (env INTERNAL_API_TOKEN)      -> talks to loyalty-service S2S endpoints
    //   - oradian.internal-token       (env ORADIAN_INTERNAL_TOKEN)  -> talks to Oradian middleware S2S endpoints
    //   - whatsapp.api-key             (env WHATSAPP_API_KEY)         -> x-api-key for the WhatsApp notification gateway
    //   - national-id.hmac-secret      (env NATIONAL_ID_HMAC_SECRET)  -> keyed hash protecting national IDs at rest
    //   - mfa.encryption-key           (env MFA_ENCRYPTION_KEY)       -> AES-GCM key encrypting TOTP secrets at rest
    //   - audit.hmac-secret            (env AUDIT_HMAC_SECRET)        -> keyed tamper-evidence tag on every audit_events row (A09)
    //   - otp.hmac-secret              (env OTP_HMAC_SECRET)          -> keyed hash protecting OTP codes at rest (A02)
    private static final List<String> SECRETS_TO_CHECK = List.of(
            "jwt.secret",
            "innbucks.internal-api-token",
            "oradian.internal-token",
            "whatsapp.api-key",
            "national-id.hmac-secret",
            "mfa.encryption-key",
            "audit.hmac-secret",
            "otp.hmac-secret"
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
        boolean deployment = active.length > 0
                && Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) {
            log.info("Secrets guard skipped (non-deployment profile: {})",
                    active.length == 0 ? "<none>" : Arrays.toString(active));
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
                    ". Override them via env vars (JWT_SECRET, INTERNAL_API_TOKEN, " +
                    "ORADIAN_INTERNAL_TOKEN, WHATSAPP_API_KEY, NATIONAL_ID_HMAC_SECRET, " +
                    "MFA_ENCRYPTION_KEY, AUDIT_HMAC_SECRET, OTP_HMAC_SECRET) before deploying."
            );
        }
        log.info("Secrets guard passed for profile {} ({} keys verified)",
                Arrays.toString(active), SECRETS_TO_CHECK.size());
    }
}
