package com.innbucks.seatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The customer-support assertion-signing key is user-service's ALONE (its own
 * Secret, referenced by that Deployment only). Present in THIS service's
 * environment under a deployment profile, it has leaked into a source every pod
 * receives, and this service could mint support assertions — so it must refuse
 * to boot rather than run holding it.
 */
class ProductionSecretsGuardSupportKeyTest {

    private static final String REAL_JWT = "prod-secret-prod-secret-prod-secret-abcd";
    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----";

    private static Environment env(String[] profiles, String supportKey) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        when(env.getProperty("jwt.secret")).thenReturn(REAL_JWT);
        when(env.getProperty("SUPPORT_ASSERTION_PRIVATE_KEY")).thenReturn(supportKey);
        return env;
    }

    @Test
    void deploymentProfile_holdingTheSupportSigningKey_refusesToBoot() {
        assertThatThrownBy(() -> new ProductionSecretsGuard(env(new String[]{"prod"}, PEM)).verifyNoPlaceholderSecrets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPORT_ASSERTION_PRIVATE_KEY")
                .hasMessageContaining("rotate it")
                // Never echo the key itself.
                .hasMessageNotContaining("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
    }

    @Test
    void emptyProfileSet_isADeployment_andIsRefusedToo() {
        assertThatThrownBy(() -> new ProductionSecretsGuard(env(new String[]{}, PEM)).verifyNoPlaceholderSecrets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPORT_ASSERTION_PRIVATE_KEY");
    }

    @Test
    void deploymentProfile_withoutTheKey_boots() {
        assertThatCode(() -> new ProductionSecretsGuard(env(new String[]{"prod"}, null)).verifyNoPlaceholderSecrets())
                .doesNotThrowAnyException();
        // A blank value (a committed placeholder line) is absence, not a key.
        assertThatCode(() -> new ProductionSecretsGuard(env(new String[]{"prod"}, "  ")).verifyNoPlaceholderSecrets())
                .doesNotThrowAnyException();
    }

    @Test
    void nonDeploymentProfile_isNotChecked() {
        assertThatCode(() -> new ProductionSecretsGuard(env(new String[]{"test"}, PEM)).verifyNoPlaceholderSecrets())
                .doesNotThrowAnyException();
    }
}
