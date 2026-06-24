package com.innbucks.userservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link MfaProperties} into the Spring context so {@code mfa.*} bindings
 * land on the bean. No other configuration lives here — the MFA collaborators
 * ({@code MfaSecretCipher}, {@code MfaPolicy}, {@code MfaTokenService},
 * {@code MfaService}) are component-scanned individually.
 */
@Configuration
@EnableConfigurationProperties(MfaProperties.class)
public class MfaConfig {
}
