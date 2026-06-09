package com.innbucks.userservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup-guard test for {@link CountryMdcConfig}. The MDC filter behaviour
 * is mechanical (put / try / finally remove) and exercised end-to-end at
 * application boot; the value-bearing assertion is that an unknown or blank
 * country pin refuses to start — that's what stops a misconfigured cell
 * from silently emitting wrong-country logs.
 */
class CountryMdcConfigTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "ZW", "KE", "ZM", "MW", "ZA", "BW", "MZ", "LS", "SZ", "NG"
    })
    @DisplayName("all ten InnBucks markets boot cleanly")
    void construct_knownCountry_boots(String iso) {
        CountryMdcConfig config = new CountryMdcConfig(iso);
        assertThat(config.getCountry()).isEqualTo(iso);
    }

    @Test
    @DisplayName("lowercase ISO normalises to uppercase")
    void construct_lowercase_normalisedToUpper() {
        assertThat(new CountryMdcConfig("zw").getCountry()).isEqualTo("ZW");
    }

    @Test
    @DisplayName("whitespace around the value is trimmed")
    void construct_padded_trimmed() {
        assertThat(new CountryMdcConfig(" ke ").getCountry()).isEqualTo("KE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",          // blank — caller forgot to set the env var
            "   ",       // whitespace-only
            "Zimbabwe",  // common typo — text instead of ISO
            "ZN",        // typo for ZW
            "US",        // valid ISO but not an InnBucks market
            "GB",
            "ZWE"        // ISO alpha-3 instead of alpha-2
    })
    @DisplayName("unknown or malformed country pin refuses to boot")
    void construct_unknown_throws(String bad) {
        assertThatThrownBy(() -> new CountryMdcConfig(bad))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("null input refuses to boot (no NPE)")
    void construct_null_throws() {
        assertThatThrownBy(() -> new CountryMdcConfig(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
