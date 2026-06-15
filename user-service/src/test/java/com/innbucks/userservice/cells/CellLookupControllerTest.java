package com.innbucks.userservice.cells;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CellLookupController}. Verifies the three response
 * shapes that anchor the FE contract:
 *
 *  - cell known: 200 with country + URL
 *  - country known, no cell deployed: 200 with country + null URL
 *  - unknown prefix: 404
 */
class CellLookupControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private CellLookupController controllerWith(String registryJson) {
        CellRegistry registry = new CellRegistry(registryJson, mapper);
        registry.parse();
        return new CellLookupController(registry);
    }

    @Test
    void cellKnown_returns200WithCountryAndBaseUrl() {
        CellLookupController c = controllerWith("{\"ZW\":\"https://api-zw.innbucks.com\"}");

        ResponseEntity<ApiResult<CellLookupController.HomeCellLookup>> resp =
                c.lookup("+263772123456");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().homeCountry()).isEqualTo("ZW");
        assertThat(resp.getBody().getData().homeBaseUrl()).isEqualTo("https://api-zw.innbucks.com");
        assertThat(resp.getBody().getMessage()).isEqualTo("Home cell resolved");
    }

    @Test
    void countryKnownButNoCellDeployedYet_returns200WithNullUrl() {
        // Prefix resolves to KE, but the registry only has ZW. The client
        // gets a meaningful "your market isn't live yet" — distinct from 404.
        CellLookupController c = controllerWith("{\"ZW\":\"https://api-zw.innbucks.com\"}");

        ResponseEntity<ApiResult<CellLookupController.HomeCellLookup>> resp =
                c.lookup("+254712345678");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getData().homeCountry()).isEqualTo("KE");
        assertThat(resp.getBody().getData().homeBaseUrl()).isNull();
        assertThat(resp.getBody().getMessage()).isEqualTo("Home country resolved (no cell deployed yet)");
    }

    @Test
    void unknownMsisdnPrefix_returns404() {
        // A US number isn't in any InnBucks market — 404, not 200 with null URL.
        CellLookupController c = controllerWith("{\"ZW\":\"https://api-zw.innbucks.com\"}");

        ResponseEntity<ApiResult<CellLookupController.HomeCellLookup>> resp =
                c.lookup("+19995551212");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMessage())
                .isEqualTo("MSISDN prefix is not in any InnBucks market");
    }

    @Test
    void blankOrNullMsisdn_returns404() {
        CellLookupController c = controllerWith("{}");
        assertThat(c.lookup(null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(c.lookup("").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(c.lookup("   ").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
