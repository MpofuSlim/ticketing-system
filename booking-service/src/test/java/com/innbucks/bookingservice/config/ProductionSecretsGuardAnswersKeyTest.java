package com.innbucks.bookingservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Buyers' answers to booking questions are sealed with BOOKING_ANSWERS_KEY
 * (V32). Without it a deployment would seal them with a throwaway key and lose
 * every answer at the next restart, so it refuses to boot; dev and tests run on
 * a throwaway key on purpose.
 */
class ProductionSecretsGuardAnswersKeyTest {

    private static Environment env(String[] profiles, String answersKey) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        when(env.getProperty("jwt.secret")).thenReturn("prod-secret-prod-secret-prod-secret-abcd");
        when(env.getProperty("app.booking.answers-key")).thenReturn(answersKey);
        return env;
    }

    @Test
    void deploymentProfile_withoutTheAnswersKey_refusesToBoot() {
        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> new ProductionSecretsGuard(env(new String[]{"prod"}, blank))
                    .verifyNoPlaceholderSecrets())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.booking.answers-key");
        }
        assertThatThrownBy(() -> new ProductionSecretsGuard(env(new String[]{}, null)).verifyNoPlaceholderSecrets())
                .hasMessageContaining("BOOKING_ANSWERS_KEY");
    }

    @Test
    void deploymentProfile_withTheKey_boots_andDevDoesNotNeedIt() {
        assertThatCode(() -> new ProductionSecretsGuard(env(new String[]{"prod"},
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")).verifyNoPlaceholderSecrets())
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProductionSecretsGuard(env(new String[]{"test"}, null)).verifyNoPlaceholderSecrets())
                .doesNotThrowAnyException();
    }
}
