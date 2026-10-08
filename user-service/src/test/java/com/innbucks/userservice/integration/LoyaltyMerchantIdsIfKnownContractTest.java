package com.innbucks.userservice.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.userservice.config.PooledHttpClient;
import com.innbucks.userservice.config.PooledHttpClientProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link LoyaltyServiceClient#merchantIdsForOrganizationIfKnown},
 * the STRICT read of loyalty-service's (InnRewards')
 * {@code GET /loyalty/internal/merchants/ids-by-organization?organizationId=}.
 *
 * <p>Rejecting a registration deletes the organization it created, so the
 * question "does InnRewards hold a merchant for it" must be ANSWERED, not
 * guessed: only a well-formed 2xx is an answer, and every other shape is
 * {@code Optional.empty()} — unknown — where the fail-open
 * {@link LoyaltyServiceClient#merchantIdsForOrganization} (pinned by
 * {@link LoyaltyMerchantIdsByOrganizationContractTest}) returns an empty list.
 * The 200 body is loyalty's plain map ({@code organizationId} +
 * {@code merchantIds}), not the {@code ApiResult} envelope; its 401 is
 * bodiless. Pure JUnit + WireMock, the client built as its bean is, pointed at
 * WireMock's port.
 */
class LoyaltyMerchantIdsIfKnownContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());

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
        return client("http://localhost:" + wireMock.port(), TOKEN, 3000);
    }

    private LoyaltyServiceClient client(String baseUrl, String token, int readTimeoutMs) {
        return new LoyaltyServiceClient(RestClient.builder(), baseUrl, 2000, readTimeoutMs, token, POOL);
    }

    private void answer(String json) {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(json)));
    }

    @Test
    @DisplayName("200 with ids: the merchants, and the wire contract is the query param + the internal token")
    void merchantsAreAnAnswer() {
        answer("""
                {"organizationId":"%s","merchantIds":["%s","%s"]}""".formatted(ORG, M1, M2));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).contains(List.of(M1, M2));

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("organizationId", equalTo(ORG.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("200 with an empty list: KNOWN to own nothing — the only shape that clears the organization")
    void emptyListIsAnAnswer() {
        answer("""
                {"organizationId":"%s","merchantIds":[]}""".formatted(ORG));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).contains(List.of());
    }

    @Test
    @DisplayName("200 without the merchantIds key: unknown, not empty")
    void absentKeyIsUnknown() {
        answer("{}");

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("200 with a malformed id: unknown — a malformed id is still a merchant, never skipped")
    void malformedIdIsUnknown() {
        answer("""
                {"organizationId":"%s","merchantIds":["not-a-uuid","%s"]}""".formatted(ORG, M1));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("200 answering for a different organization: unknown")
    void echoOfAnotherOrganizationIsUnknown() {
        answer("""
                {"organizationId":"%s","merchantIds":[]}""".formatted(UUID.randomUUID()));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("200 with an HTML page (an edge block page): unknown")
    void htmlIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>Request Rejected</html>")));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("204 / a 2xx with no body: unknown")
    void noBodyIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(204)));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("302: unknown — the pool never follows a redirect (it would re-send the internal token)")
    void redirectIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://localhost:" + wireMock.port() + "/elsewhere")));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/elsewhere")));
    }

    @Test
    @DisplayName("401 (token rejected, bodiless): unknown — a 4xx is not an answer")
    void rejectedTokenIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("404 (a loyalty too old to serve the endpoint): unknown")
    void unknownEndpointIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("500: unknown")
    void serverErrorIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"boom\"}")));

        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("Read timeout: unknown")
    void readTimeoutIsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("""
                {"organizationId":"%s","merchantIds":[]}""".formatted(ORG)).withFixedDelay(1500)));

        assertThat(client("http://localhost:" + wireMock.port(), TOKEN, 300)
                .merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("Connect refused: unknown")
    void connectRefusedIsUnknown() {
        // A separate client at a known-closed port; never stop/restart the
        // shared WireMock, whose second start gets a different dynamic port.
        assertThat(client("http://localhost:1", TOKEN, 3000).merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
    }

    @Test
    @DisplayName("Guard rails: no token configured, or no organization, is unknown and never hits the wire")
    void guardRailsNeverCallOut() {
        assertThat(client("http://localhost:" + wireMock.port(), "  ", 3000)
                .merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
        assertThat(client("http://localhost:" + wireMock.port(), null, 3000)
                .merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
        assertThat(client().merchantIdsForOrganizationIfKnown(null)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/loyalty/internal/merchants/.*")));
    }

    @Test
    @DisplayName("The fail-open variant still answers an empty list for the same unknowns (ShopStaffService relies on it)")
    void failOpenVariantIsUnchanged() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(client().merchantIdsForOrganization(ORG)).isEmpty();
        assertThat(client().merchantIdsForOrganizationIfKnown(ORG)).isEmpty();
        // Same wire contract for both.
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("organizationId", equalTo(ORG.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }
}
