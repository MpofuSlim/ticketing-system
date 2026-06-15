package com.innbucks.userservice.cells;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CellRegistry} — JSON parsing, empty/invalid defaults,
 * case-insensitive lookup. Pure JUnit, no Spring.
 *
 * <p>Why fail-open: an invalid registry shouldn't crash boot — the affinity
 * checker still works (no URL in the 409, FE falls back to /cells/lookup).
 * These cases prove that.
 */
class CellRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CellRegistry build(String json) {
        CellRegistry r = new CellRegistry(json, mapper);
        r.parse();
        return r;
    }

    @Test
    void parsesTwoCells_lookupIsCaseInsensitive() {
        CellRegistry r = build("{\"ZW\":\"https://api-zw.innbucks.com\",\"KE\":\"https://api-ke.innbucks.com\"}");

        assertThat(r.countries()).containsExactlyInAnyOrder("ZW", "KE");
        assertThat(r.baseUrlFor("ZW")).hasValue("https://api-zw.innbucks.com");
        assertThat(r.baseUrlFor("zw")).hasValue("https://api-zw.innbucks.com");
        assertThat(r.baseUrlFor("ke")).hasValue("https://api-ke.innbucks.com");
        assertThat(r.baseUrlFor("ng")).isEmpty();
        assertThat(r.baseUrlFor(null)).isEmpty();
        assertThat(r.baseUrlFor("")).isEmpty();
    }

    @Test
    void emptyJson_isLegal_yieldsEmptyRegistry() {
        CellRegistry r = build("{}");
        assertThat(r.countries()).isEmpty();
        assertThat(r.baseUrlFor("ZW")).isEmpty();
    }

    @Test
    void nullAndBlank_treatedAsEmpty() {
        assertThat(build(null).countries()).isEmpty();
        assertThat(build("   ").countries()).isEmpty();
    }

    @Test
    void invalidJson_failsOpen_doesNotThrow() {
        // The constructor must not blow up boot when an operator typos the env var.
        // Empty registry is the safe fall-back — wrong_cell still tells the FE the
        // home country, just without a URL.
        CellRegistry r = build("not-json-at-all");
        assertThat(r.countries()).isEmpty();
    }

    @Test
    void entriesWithBlankKeyOrValue_areDropped() {
        CellRegistry r = build("{\"ZW\":\"https://api-zw.innbucks.com\",\"\":\"https://api-na.innbucks.com\",\"KE\":\"\"}");
        assertThat(r.countries()).containsExactly("ZW");
    }

    @Test
    void keysAreNormalisedToUpperCase_andValuesTrimmed() {
        CellRegistry r = build("{\"zw\":\"  https://api-zw.innbucks.com  \"}");
        assertThat(r.countries()).containsExactly("ZW");
        assertThat(r.baseUrlFor("ZW")).hasValue("https://api-zw.innbucks.com");
    }
}
