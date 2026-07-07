package com.innbucks.userservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the OWASP A02-M3 fail-CLOSED contract of {@link ProductionSecretsGuard}:
 * an EMPTY active-profile set is treated as a deployment and must refuse to boot
 * on placeholder / weak secrets, so a prod container launched without
 * {@code SPRING_PROFILES_ACTIVE} can no longer silently run on the change-me
 * defaults. A non-deployment profile (dev/test/it/local) still opts out.
 */
class ProductionSecretsGuardTest {

    private static final String REAL_JWT = "prod-secret-prod-secret-prod-secret-abcd"; // 40 chars, no "change-me"

    private ProductionSecretsGuard guard(Environment env) {
        return new ProductionSecretsGuard(env);
    }

    @Test
    void emptyProfile_withPlaceholderSecret_failsClosed() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});      // no SPRING_PROFILES_ACTIVE
        when(env.getProperty("jwt.secret")).thenReturn("change-me-change-me-change-me-change-me");

        assertThatThrownBy(() -> guard(env).verifyNoPlaceholderSecrets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void emptyProfile_withRealSecrets_boots() {
        // Empty profile now runs the guard (fail-closed), but real secrets pass.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});
        when(env.getProperty("jwt.secret")).thenReturn(REAL_JWT);
        // every other guarded secret + redis password unstubbed -> null -> not an offender

        assertThatCode(() -> guard(env).verifyNoPlaceholderSecrets()).doesNotThrowAnyException();
    }

    @Test
    void nonDeploymentProfile_withPlaceholders_skips() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"test"});
        when(env.getProperty("jwt.secret")).thenReturn("change-me-change-me-change-me-change-me");

        assertThatCode(() -> guard(env).verifyNoPlaceholderSecrets()).doesNotThrowAnyException();
    }

    @Test
    void deploymentProfile_withPlaceholder_stillFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(env.getProperty("jwt.secret")).thenReturn("change-me-change-me-change-me-change-me");

        assertThatThrownBy(() -> guard(env).verifyNoPlaceholderSecrets())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deploymentProfile_blankRedisPassword_failsClosed() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(env.getProperty("jwt.secret")).thenReturn(REAL_JWT);
        when(env.getProperty("spring.data.redis.password")).thenReturn("");   // blank == no Redis AUTH

        assertThatThrownBy(() -> guard(env).verifyNoPlaceholderSecrets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis");
    }

    @Test
    void deploymentProfile_taggedAlsoTest_isTreatedAsNonDeployment() {
        // {prod, test} — the presence of a non-deployment profile wins, so a
        // test that pins a prod-like context (e.g. SwaggerSecurityConfigProdDisabledTest)
        // still skips the guard.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod", "test"});
        when(env.getProperty("jwt.secret")).thenReturn("change-me-change-me-change-me-change-me");

        assertThatCode(() -> guard(env).verifyNoPlaceholderSecrets()).doesNotThrowAnyException();
    }
}
