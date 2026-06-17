package com.innbucks.userservice.cells;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

/**
 * Unit tests for {@link CellAffinityChecker} — the three guard surfaces
 * (MSISDN, identifier, country claim) plus the registry-lookup behaviour
 * that makes the 409 include a homeBaseUrl when known.
 */
class CellAffinityCheckerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CellAffinityChecker zwChecker(String registryJson) {
        CellRegistry registry = new CellRegistry(registryJson, mapper);
        registry.parse();
        CellAffinityChecker checker = new CellAffinityChecker("ZW", registry);
        checker.describe();
        return checker;
    }

    // --- requireDomesticMsisdn ----------------------------------------------

    @Test
    void requireDomesticMsisdn_domesticZw_passes() {
        zwChecker("{}").requireDomesticMsisdn("+263772123456");
    }

    @Test
    void requireDomesticMsisdn_foreignKe_throwsWithHomeCountryAndBaseUrl() {
        CellAffinityChecker checker = zwChecker(
                "{\"KE\":\"https://dtx.innbucks.co.ke\"}");

        assertThatThrownBy(() -> checker.requireDomesticMsisdn("+254712345678"))
                .isInstanceOf(WrongCellException.class)
                .asInstanceOf(throwable(WrongCellException.class))
                .satisfies(ex -> {
                    assertThat(ex.getHomeCountry()).isEqualTo("KE");
                    assertThat(ex.getHomeBaseUrl()).isEqualTo("https://dtx.innbucks.co.ke");
                });
    }

    @Test
    void requireDomesticMsisdn_foreignKe_noRegistryEntry_throwsWithNullBaseUrl() {
        // KE is foreign, registry has only ZW — wrong_cell still surfaces, just
        // without a redirect URL. FE falls back to GET /cells/lookup.
        CellAffinityChecker checker = zwChecker("{\"ZW\":\"https://dtx.innbucks.co.zw\"}");

        assertThatThrownBy(() -> checker.requireDomesticMsisdn("+254712345678"))
                .isInstanceOf(WrongCellException.class)
                .asInstanceOf(throwable(WrongCellException.class))
                .satisfies(ex -> {
                    assertThat(ex.getHomeCountry()).isEqualTo("KE");
                    assertThat(ex.getHomeBaseUrl()).isNull();
                });
    }

    @Test
    void requireDomesticMsisdn_unresolvablePrefix_isNoOp() {
        // A non-InnBucks-market prefix is not a wrong-cell signal — let the
        // existing MSISDN validation handle it, don't fake a routing error.
        zwChecker("{}").requireDomesticMsisdn("+19995551212");
    }

    @Test
    void requireDomesticMsisdn_nullOrBlank_isNoOp() {
        zwChecker("{}").requireDomesticMsisdn(null);
        zwChecker("{}").requireDomesticMsisdn("");
        zwChecker("{}").requireDomesticMsisdn("   ");
    }

    // --- requireDomesticIfMsisdn (login identifier) -------------------------

    @Test
    void requireDomesticIfMsisdn_emailIdentifier_isNoOp() {
        // We cannot route by email — let the request through; the JWT
        // affinity check is the safety net post-auth.
        zwChecker("{}").requireDomesticIfMsisdn("nairobi-customer@example.com");
    }

    @Test
    void requireDomesticIfMsisdn_phoneIdentifier_appliesAffinity() {
        CellAffinityChecker checker = zwChecker("{}");

        assertThatThrownBy(() -> checker.requireDomesticIfMsisdn("+254712345678"))
                .isInstanceOf(WrongCellException.class);

        // Domestic phone — passes.
        checker.requireDomesticIfMsisdn("+263772123456");
    }

    // --- requireDomesticCountry (JWT homeCountry claim) ---------------------

    @Test
    void requireDomesticCountry_matchingClaim_passes() {
        zwChecker("{}").requireDomesticCountry("ZW");
        zwChecker("{}").requireDomesticCountry("zw"); // case-insensitive
    }

    @Test
    void requireDomesticCountry_nullOrBlank_isNoOp() {
        // Legacy tokens minted before the homeCountry claim existed must still
        // work — the JWT signature is the auth. They just don't trigger routing.
        zwChecker("{}").requireDomesticCountry(null);
        zwChecker("{}").requireDomesticCountry("");
        zwChecker("{}").requireDomesticCountry("   ");
    }

    @Test
    void requireDomesticCountry_foreignClaim_throwsWithRedirect() {
        CellAffinityChecker checker = zwChecker(
                "{\"KE\":\"https://dtx.innbucks.co.ke\"}");

        assertThatThrownBy(() -> checker.requireDomesticCountry("KE"))
                .isInstanceOf(WrongCellException.class)
                .asInstanceOf(throwable(WrongCellException.class))
                .satisfies(ex -> {
                    assertThat(ex.getHomeCountry()).isEqualTo("KE");
                    assertThat(ex.getHomeBaseUrl()).isEqualTo("https://dtx.innbucks.co.ke");
                });
    }
}
