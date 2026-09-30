package com.innbucks.apigateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * Refuses to boot under a deployment profile when customer support's
 * assertion-signing key ({@code SUPPORT_ASSERTION_PRIVATE_KEY}) is in this
 * pod's environment — the same check the data services' ProductionSecretsGuards
 * make, here because the gateway has no such guard and is the most exposed pod
 * in the cell.
 *
 * <p>The key belongs to user-service ALONE (its own Secret,
 * {@code user-service-support-signing}, referenced by that Deployment only):
 * whoever holds it can mint {@code X-Support-Assertion}s that booking, payment,
 * loyalty and the marketplace accept as a support agent's call. Here it means it
 * leaked into {@code cell-zw-secrets} or the cell ConfigMap, which every pod
 * receives through {@code envFrom}. "Deployment" is the fleet's rule: an
 * active-profile set with no {@code dev/test/it/local} profile, the EMPTY set
 * included.
 */
@Configuration
public class SupportKeyCustodyGuard {

    static final String SUPPORT_ASSERTION_PRIVATE_KEY = "SUPPORT_ASSERTION_PRIVATE_KEY";
    private static final Set<String> NON_DEPLOYMENT_PROFILES = Set.of("dev", "test", "it", "local");

    private final Environment env;

    public SupportKeyCustodyGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void refuseTheSupportSigningKey() {
        String[] active = env.getActiveProfiles();
        boolean deployment = Arrays.stream(active).noneMatch(NON_DEPLOYMENT_PROFILES::contains);
        if (!deployment) return;
        String key = env.getProperty(SUPPORT_ASSERTION_PRIVATE_KEY);
        if (key != null && !key.isBlank()) {
            throw new IllegalStateException("Refusing to start under deployment profile " + Arrays.toString(active)
                    + ": " + SUPPORT_ASSERTION_PRIVATE_KEY + " is set in the gateway's environment. It belongs to "
                    + "user-service alone (the user-service-support-signing Secret); here it means the key has "
                    + "leaked into a source every pod receives. Remove it from cell-zw-secrets / the cell "
                    + "ConfigMap and rotate it.");
        }
    }
}
