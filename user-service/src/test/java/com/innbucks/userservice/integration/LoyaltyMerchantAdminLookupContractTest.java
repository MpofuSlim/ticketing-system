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
 * Contract test for {@link LoyaltyServiceClient#adminEmailForMerchant} against
 * loyalty-service's {@code GET /loyalty/internal/merchants/{id}/admin-email}.
 *
 * <p>This one earns its keep more than most: the endpoint lives in a DIFFERENT
 * REPOSITORY ({@code MpofuSlim/InnRewards}), so nothing in this build would
 * notice loyalty reshaping the envelope. Every stub below transcribes a shape
 * that service actually returns — read off its
 * {@code InternalMerchantLookupController} — and each non-2xx case is one of
 * the ways the chain legitimately resolves nobody.
 *
 * <p>Pure JUnit + WireMock, no {@code @SpringBootTest}: the client is built the
 * same way its bean is, just pointed at WireMock's port. The production
 * constructor takes the {@code @LoadBalanced} builder so {@code loyalty-service}
 * resolves through Eureka; a plain builder with an absolute base URL exercises
 * the identical code path without a registry.
 */
class LoyaltyMerchantAdminLookupContractTest {

    private static final String TOKEN = "the-shared-secret";
    private static final UUID MERCHANT = UUID.fromString("b3f1c9d2-4a77-4e21-9c60-11ab22cd33ef");
    private static final String PATH = "/loyalty/internal/merchants/" + MERCHANT + "/admin-email";

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
    @DisplayName("200 with an admin email: returned trimmed, and the token rides the request")
    void resolvesTheAdminEmail() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"merchantId":"%s","adminEmail":"  chipo@merchant.test  "}""".formatted(MERCHANT))));

        assertThat(client().adminEmailForMerchant(MERCHANT)).contains("chipo@merchant.test");

        // The OUTBOUND contract matters as much as the response: without the
        // shared secret loyalty answers 401 and every merchant silently
        // resolves to nobody.
        wireMock.verify(getRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("200 with a NULL adminEmail: empty — the merchant exists, nobody runs it")
    void nullAdminEmailIsEmpty() {
        // Loyalty's deliberate shape for a merchant with nobody on file: a 200,
        // not a 404, because the consumer's next step is the same either way.
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"merchantId":"%s","adminEmail":null}""".formatted(MERCHANT))));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("200 with the key absent entirely: empty, never a crash")
    void absentKeyIsEmpty() {
        // Gson drops nulls, so loyalty may omit the key rather than send null.
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"merchantId":"%s"}""".formatted(MERCHANT))));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("200 with a BLANK adminEmail: empty — a blank is not an address")
    void blankAdminEmailIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(okJson("""
                {"merchantId":"%s","adminEmail":"   "}""".formatted(MERCHANT))));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("404 (no such merchant): empty, not an exception")
    void unknownMerchantIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("401 (token rejected): empty, not an exception")
    void rejectedTokenIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("500: empty, not an exception — a loyalty blip costs a message, never an error")
    void serverErrorIsEmpty() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("Connect refused: empty — the only caller is a notification lookup")
    void connectRefusedIsEmpty() {
        // A separate client at a known-closed port; never stop/restart the
        // shared WireMock, whose second start gets a different dynamic port.
        LoyaltyServiceClient offline = client("http://localhost:1", TOKEN);

        assertThat(offline.adminEmailForMerchant(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("Guard rails: a null merchant and an unconfigured token never hit the wire")
    void guardRailsNeverCallOut() {
        assertThat(client().adminEmailForMerchant(null)).isEmpty();
        assertThat(client("http://localhost:" + wireMock.port(), "  ")
                .adminEmailForMerchant(MERCHANT)).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/loyalty/internal/merchants/.*")));
    }

    @Test
    @DisplayName("A merchant id with no admin never leaks into the path of another lookup")
    void addressesTheMerchantItWasAsked() {
        UUID other = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/loyalty/internal/merchants/" + other + "/admin-email"))
                .willReturn(okJson("""
                        {"merchantId":"%s","adminEmail":"someone@else.test"}""".formatted(other))));
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client().adminEmailForMerchant(MERCHANT)).isEmpty();
        assertThat(client().adminEmailForMerchant(other)).contains("someone@else.test");
    }
}
