package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 2FA / TOTP configuration. Bound from {@code mfa.*}.
 *
 * <p>{@code encryptionKey} is the AES-GCM key that protects TOTP secrets at
 * rest in {@code users.mfa_secret}. It must be a 32-byte value, supplied here
 * Base64-encoded — anything else makes
 * {@link com.innbucks.userservice.security.MfaSecretCipher} refuse to boot.
 * ProductionSecretsGuard refuses {@code change-me-*} under the prod profile.
 *
 * <p>{@code issuer} is the label authenticator apps show next to the account
 * (e.g. "InnBucks" or "SwiftInn") — purely cosmetic, no security consequence.
 *
 * <p>{@code mfaTokenTtl} bounds how long a step-1 login (password OK, awaiting
 * the TOTP code) stays valid before the FE has to start over.
 */
@Data
@ConfigurationProperties(prefix = "mfa")
public class MfaProperties {

    /** Base64-encoded 32-byte AES-GCM key used to encrypt TOTP secrets at rest. */
    private String encryptionKey;

    /** Issuer label embedded in the otpauth:// URI / shown in authenticator apps. */
    private String issuer = "InnBucks";

    /** TTL of the step-1 mfaToken handed back by /auth/login when 2FA is required. */
    private Duration mfaTokenTtl = Duration.ofMinutes(5);

    /** Number of single-use backup codes minted on enrollment. */
    private int backupCodeCount = 10;

    /**
     * How many days a "remember this device" trust token skips the step-1 MFA
     * challenge before it expires. Applied to {@code devices.mfa_trusted_until}
     * when a user opts in at step-2 of an MFA login.
     */
    private int trustedDeviceDays = 30;
}
