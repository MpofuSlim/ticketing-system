package com.innbucks.userservice.corebanking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Validates {@code innbucks.core-banking.provider} at startup so a typo'd or
 * not-yet-implemented value fails the boot with a readable message instead
 * of Spring's opaque "no qualifying bean of type CoreBankingPort". Mirrors
 * the {@code CountryMdcConfig} fail-fast pattern.
 *
 * <p>Allowed values today: {@code oradian} (the default — current behaviour
 * for every cell). {@code veengu} joins the allowlist when the Veengu
 * adapter lands (phase 2 of the provider split); until then a cell trying
 * to flip early gets this clear refusal rather than a half-wired context.
 */
@Slf4j
@Configuration
public class CoreBankingProviderConfig {

    private static final Set<String> SUPPORTED = Set.of("oradian");

    public CoreBankingProviderConfig(
            @Value("${innbucks.core-banking.provider:oradian}") String provider) {
        if (provider == null || !SUPPORTED.contains(provider)) {
            throw new IllegalStateException(
                    "innbucks.core-banking.provider='" + provider + "' is not supported. "
                            + "Supported: " + SUPPORTED + ". "
                            + "('veengu' arrives with the Veengu adapter — phase 2 of the provider split.)");
        }
        log.info("Core-banking provider for this cell: {}", provider);
    }
}
