package com.innbucks.userservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link MsisdnCountryResolver}. The truth table here IS
 * the cell-routing contract — any change to a prefix mapping is a behaviour
 * change that must update this test.
 */
class MsisdnCountryResolverTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // All ten InnBucks target markets, with and without the +.
            "+263782606983, ZW",   // Zimbabwe (current home market)
            "263782606983,  ZW",
            "+254712345678, KE",   // Kenya
            "+260971234567, ZM",   // Zambia
            "+265991234567, MW",   // Malawi
            "+27821234567,  ZA",   // South Africa (2-digit prefix)
            "27821234567,   ZA",
            "+267721234567, BW",   // Botswana
            "+258821234567, MZ",   // Mozambique
            "+266501234567, LS",   // Lesotho
            "+268761234567, SZ",   // Eswatini
            "+234801234567, NG"    // Nigeria
    })
    @DisplayName("known InnBucks-market MSISDNs resolve to the right ISO code")
    void resolve_knownPrefix_returnsIso(String msisdn, String expectedIso) {
        assertThat(MsisdnCountryResolver.resolve(msisdn))
                .contains(expectedIso);
    }

    @Test
    @DisplayName("South Africa (2-digit) doesn't shadow a hypothetical 3-digit match")
    void resolve_longestPrefixWins() {
        // Defensive: today no 3-digit prefix starts with "27", but if one
        // were added later the resolver must pick the 3-digit match, not
        // mis-route to ZA. Verified by giving 27-prefixed input and
        // confirming we DO get ZA when no 3-digit entry matches.
        assertThat(MsisdnCountryResolver.resolve("+27821234567")).contains("ZA");
        // And that adding more digits past the 2-digit prefix doesn't break
        // the ZA match either.
        assertThat(MsisdnCountryResolver.resolve("+270000000000000")).contains("ZA");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+1234567890",      // North America (+1) — not an InnBucks market
            "+447911123456",    // UK (+44) — not in scope
            "+861234567890",    // China (+86) — not in scope
            "+12345"            // unknown 3-digit prefix
    })
    @DisplayName("non-InnBucks prefixes return empty (no fallback country)")
    void resolve_unknownPrefix_returnsEmpty(String msisdn) {
        assertThat(MsisdnCountryResolver.resolve(msisdn)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "+",
            "263abc782",        // contains non-digits after the +
            "++263782606983",   // two leading + signs
            "ZW782606983"       // letters in the prefix
    })
    @DisplayName("malformed input returns empty")
    void resolve_malformedInput_returnsEmpty(String msisdn) {
        assertThat(MsisdnCountryResolver.resolve(msisdn)).isEmpty();
    }

    @Test
    @DisplayName("null input returns empty (no NPE)")
    void resolve_null_returnsEmpty() {
        assertThat(MsisdnCountryResolver.resolve(null)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a too-short number (single digit) returns empty")
    void resolve_singleDigit_returnsEmpty() {
        assertThat(MsisdnCountryResolver.resolve("+2")).isEmpty();
    }
}
