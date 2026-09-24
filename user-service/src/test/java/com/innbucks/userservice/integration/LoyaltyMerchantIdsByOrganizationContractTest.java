package com.innbucks.userservice.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link LoyaltyServiceClient#merchantIdsForOrganization}
 * against loyalty-service's
 * {@code GET /loyalty/internal/merchants/ids-by-organization?organizationId=}.
 *
 * <p>The endpoint lives in a DIFFERENT REPOSITORY ({@code MpofuSlim/InnRewards}),
 * so nothing in this build would notice loyalty reshaping it. The 200 body is
 * loyalty's plain-map shape ({@code organizationId} + {@code merchantIds}), not
 * the {@code ApiResult} envelope — loyalty's internal surface does not wrap —
 * and each non-2xx case is one of the ways the lookup legitimately resolves
 * nothing. Every miss is an empty list, because the only caller is an
 * ownership check that must fail closed.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the client is built the
 * same way its bean is, pointed at WireMock's port. The production constructor
 * takes the {@code @LoadBalanced} builder so {@code loyalty-service} resolves
 * through Eureka; a plain builder with an absolute base URL exercises the
 * identical code path without a registry.
 */
class LoyaltyMerchantIdsByOrganizationContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID ORG = UUID.fromString("7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final UUID M1 = UUID.fromString("b3f1c9d2-4a77-4e21-9c60-11ab22cd33ef");
    private static final UUID M2 = UUID.fromString("c4e2dae3-5b88-4f32-8d71-22bc33de44f0");
    private static final String PATH = "/loyalty/internal/merchants/ids-by-organization";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private LoyaltyServiceClient client() {
        return client("http://localhost:" + wireMock.port(), TOKEN);
    }

    private LoyaltyServiceClient client(String baseUrl, String token) {
        return new LoyaltyServiceClient(RestClient.builder(), baseUrl, 2000, 3000, token);
    }

    @Test
    @DisplayName("200: every merchant the organization owns, and the wire contract is the query param + token")
    void resolvesTheOrganizationsMerchants() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"organizationId":"%s","merchantIds":["%s","%s"]}""".formatted(ORG, M1, M2))));

        assertThat(client().merchantIdsForOrganization(ORG)).containsExactly(M1, M2);

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("organizationId", equalTo(ORG.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("200 with none: an organization with no loyalty merchant owns nothing")
    void noMerchantsIsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"organizationId":"%s","merchantIds":[]}""".formatted(ORG))));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("A malformed id is skipped, the rest still count")
    void malformedIdIsSkipped() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"organizationId":"%s","merchantIds":["not-a-uuid","%s"]}""".formatted(ORG, M1))));

        assertThat(client().merchantIdsForOrganization(ORG)).containsExactly(M1);
    }

    @Test
    @DisplayName("A body without the key is empty, not an error")
    void absentKeyIsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{}")));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("401 (token rejected): empty — fail closed")
    void rejectedTokenIsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("404 (a loyalty too old to serve the endpoint): empty — fail closed")
    void unknownEndpointIsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("500: empty — fail closed")
    void serverErrorIsEmpty() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("Connect refused: empty — fail closed")
    void connectRefusedIsEmpty() {
        // A separate client at a known-closed port; never stop/restart the
        // shared WireMock, whose second start gets a different dynamic port.
        LoyaltyServiceClient offline = client("http://localhost:1", TOKEN);

        assertThat(offline.merchantIdsForOrganization(ORG)).isEmpty();
    }

    @Test
    @DisplayName("Guard rails: a null organization and an unconfigured token never hit the wire")
    void guardRailsNeverCallOut() {
        assertThat(client().merchantIdsForOrganization(null)).isEmpty();
        assertThat(client("http://localhost:" + wireMock.port(), "  ")
                .merchantIdsForOrganization(ORG)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/loyalty/internal/merchants/.*")));
    }
}
