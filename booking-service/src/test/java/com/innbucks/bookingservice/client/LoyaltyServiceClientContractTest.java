package com.innbucks.bookingservice.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.bookingservice.dto.LoyaltyEarnRequest;
import com.innbucks.bookingservice.dto.LoyaltyRedeemRequest;
import com.innbucks.bookingservice.dto.LoyaltyRuleResponse;
import feign.Feign;
import feign.FeignException;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link LoyaltyServiceClient}: pins the booking↔loyalty
 * ticketing earn/redeem/rule wire shapes (organizer = merchant, X-Internal-Token,
 * booking id as reference). Pure JUnit + WireMock; the production Feign client is
 * built standalone with the same SpringMVC contract its config bean produces,
 * pointed at WireMock — no Spring context, no circuit-breaker fallback.
 */
class LoyaltyServiceClientContractTest {

    private static final String TOKEN = "test-internal-token";
    private static final UUID ORGANIZER = UUID.fromString("0a571c1c-7c75-4000-a000-0000000000aa");
    private static final String PHONE = "+263771234567";

    private static WireMockServer wireMock;
    private static LoyaltyServiceClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        client = build("http://localhost:" + wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private static LoyaltyServiceClient build(String baseUrl) {
        return Feign.builder()
                .contract(new SpringMvcContract())
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .target(LoyaltyServiceClient.class, baseUrl);
    }

    @Test
    @DisplayName("getRule: 200 → rates parsed; organizerUuid query + X-Internal-Token on the wire")
    void getRule_happyPath() {
        wireMock.stubFor(get(urlPathEqualTo("/loyalty/internal/ticketing/rule"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"tenantId\":\"0a571c1c-7c75-4000-a000-000000000001\","
                                + "\"merchantId\":\"b4c0d2e3-2345-6789-abcd-ef0123456789\","
                                + "\"earnRate\":1,\"redeemRate\":100,\"currency\":\"USD\",\"active\":true}")));

        LoyaltyRuleResponse rule = client.getRule(ORGANIZER, TOKEN);

        assertThat(rule.getEarnRate()).isEqualByComparingTo("1");
        assertThat(rule.getRedeemRate()).isEqualByComparingTo("100");
        assertThat(rule.isActive()).isTrue();
        wireMock.verify(getRequestedFor(urlPathEqualTo("/loyalty/internal/ticketing/rule"))
                .withQueryParam("organizerUuid", equalTo(ORGANIZER.toString()))
                .withHeader("X-Internal-Token", equalTo(TOKEN)));
    }

    @Test
    @DisplayName("earn: 200 → returns; outbound body shape + X-Internal-Token verified")
    void earn_happyPath() {
        wireMock.stubFor(post(urlEqualTo("/loyalty/internal/ticketing/earn"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        client.earn(LoyaltyEarnRequest.builder()
                .organizerUuid(ORGANIZER).phoneNumber(PHONE)
                .cashAmount(new BigDecimal("25.00")).reference("BK-1").build(), TOKEN);

        wireMock.verify(postRequestedFor(urlEqualTo("/loyalty/internal/ticketing/earn"))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.organizerUuid", equalTo(ORGANIZER.toString())))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo(PHONE)))
                .withRequestBody(matchingJsonPath("$.cashAmount"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("BK-1"))));
    }

    @Test
    @DisplayName("redeem: 200 → returns; outbound body shape + X-Internal-Token verified")
    void redeem_happyPath() {
        wireMock.stubFor(post(urlEqualTo("/loyalty/internal/ticketing/redeem"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"balanceAfter\":70}")));

        client.redeem(LoyaltyRedeemRequest.builder()
                .organizerUuid(ORGANIZER).phoneNumber(PHONE)
                .points(new BigDecimal("500")).reference("BK-1").build(), TOKEN);

        wireMock.verify(postRequestedFor(urlEqualTo("/loyalty/internal/ticketing/redeem"))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.organizerUuid", equalTo(ORGANIZER.toString())))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo(PHONE)))
                .withRequestBody(matchingJsonPath("$.points"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("BK-1"))));
    }

    @Test
    @DisplayName("redeem: loyalty 4xx (e.g. insufficient) → FeignException carrying the status")
    void redeem_4xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/loyalty/internal/ticketing/redeem"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"not enough points\"}")));

        assertThatThrownBy(() -> client.redeem(LoyaltyRedeemRequest.builder()
                .organizerUuid(ORGANIZER).phoneNumber(PHONE)
                .points(new BigDecimal("999999")).reference("BK-2").build(), TOKEN))
                .isInstanceOf(FeignException.class)
                .satisfies(e -> assertThat(((FeignException) e).status()).isEqualTo(400));
    }

    @Test
    @DisplayName("earn: loyalty 5xx → FeignException (booking queues a retry)")
    void earn_5xx_throws() {
        wireMock.stubFor(post(urlEqualTo("/loyalty/internal/ticketing/earn"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.earn(LoyaltyEarnRequest.builder()
                .organizerUuid(ORGANIZER).phoneNumber(PHONE)
                .cashAmount(new BigDecimal("25.00")).reference("BK-3").build(), TOKEN))
                .isInstanceOf(FeignException.class);
    }

    @Test
    @DisplayName("connection refused → FeignException")
    void earn_connectionRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        LoyaltyServiceClient dead = build("http://localhost:" + closedPort);
        assertThatThrownBy(() -> dead.earn(LoyaltyEarnRequest.builder()
                .organizerUuid(ORGANIZER).phoneNumber(PHONE)
                .cashAmount(new BigDecimal("25.00")).reference("BK-4").build(), TOKEN))
                .isInstanceOf(FeignException.class);
    }
}
