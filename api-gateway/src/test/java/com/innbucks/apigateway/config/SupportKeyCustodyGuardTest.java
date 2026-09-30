package com.innbucks.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The gateway refuses to run holding user-service's support assertion-signing key. */
class SupportKeyCustodyGuardTest {

    private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----";

    private static Environment env(String[] profiles, String key) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(profiles);
        when(env.getProperty("SUPPORT_ASSERTION_PRIVATE_KEY")).thenReturn(key);
        return env;
    }

    @Test
    void deploymentHoldingTheKey_refusesToBoot_withoutEchoingIt() {
        assertThatThrownBy(() -> new SupportKeyCustodyGuard(env(new String[]{"prod"}, PEM)).refuseTheSupportSigningKey())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPPORT_ASSERTION_PRIVATE_KEY")
                .hasMessageNotContaining("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
        assertThatThrownBy(() -> new SupportKeyCustodyGuard(env(new String[]{}, PEM)).refuseTheSupportSigningKey())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutTheKey_orUnderATestProfile_boots() {
        assertThatCode(() -> new SupportKeyCustodyGuard(env(new String[]{"prod"}, null)).refuseTheSupportSigningKey())
                .doesNotThrowAnyException();
        assertThatCode(() -> new SupportKeyCustodyGuard(env(new String[]{"prod"}, "")).refuseTheSupportSigningKey())
                .doesNotThrowAnyException();
        assertThatCode(() -> new SupportKeyCustodyGuard(env(new String[]{"test"}, PEM)).refuseTheSupportSigningKey())
                .doesNotThrowAnyException();
    }
}
