package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.config.PooledHttpClientProperties;
import com.innbucks.userservice.config.PooledHttpClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for staging's {@code POST /auth/client-service} as DTX calls it
 * (contract §5.1). The request and the {@code {"accessToken": "…"}} response are
 * transcribed from the contract document itself; the error and non-JSON cases
 * pin the client's guard rails — a 2xx without a token is never handed to the
 * app, and an unconfigured credential never reaches the network.
 */
class StagingClientServiceClientContractTest {

    /** The production transport: the module's shared pool (CLAUDE.md, "Outbound HTTP clients are pooled"). */
    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());

    private static final String PATH = "/auth/client-service";
    private static final Instant T0 = Instant.parse("2026-09-29T09:58:12Z");
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

    private static DeviceSecurityProperties.Staging config(int port) {
        DeviceSecurityProperties.Staging c = new DeviceSecurityProperties.Staging();
        c.setBaseUrl("http://localhost:" + port);
        c.setApiKey("staging-api-key");
        c.setUsername("dtx-client");
        c.setPassword("dtx-secret");
        c.setConnectTimeoutMs(500);
        c.setReadTimeoutMs(2000);
        return c;
    }

    private static StagingClientServiceClient client(DeviceSecurityProperties.Staging c) {
        return new StagingClientServiceClient(c, Clock.fixed(T0, ZoneOffset.UTC), POOL);
    }

    private static String jwtExpiringAt(Instant exp) {
        String payload = "{\"sub\":\"client-service\",\"exp\":" + exp.getEpochSecond() + "}";
        return "eyJhbGciOiJIUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    @DisplayName("posts {username,password} with X-Api-Key and returns the accessToken, expiry from the JWT's exp")
    void happyPath_pinsTheWireShape() {
        String token = jwtExpiringAt(T0.plusSeconds(900));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"accessToken\":\"" + token + "\"}")));

        StagingClientServiceClient.ClientServiceToken t = client(config(wireMock.port())).token();

        assertThat(t.accessToken()).isEqualTo(token);
        assertThat(t.expiresAt()).isEqualTo(LocalDateTime.ofInstant(T0.plusSeconds(900), ZoneOffset.UTC));
        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("X-Api-Key", equalTo("staging-api-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("dtx-client")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("dtx-secret"))));
    }

    @Test
    @DisplayName("the company-level token is fetched once and reused while it has life left")
    void token_isCached() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(okJson("{\"accessToken\":\"" + jwtExpiringAt(T0.plusSeconds(900)) + "\"}")));
        StagingClientServiceClient c = client(config(wireMock.port()));
        c.token();
        c.token();
        c.token();
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        c.invalidate();
        c.token();
        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void opaqueToken_usesExpiresIn_elseTheFallback() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"accessToken\":\"opaque\",\"expiresIn\":300}")));
        assertThat(client(config(wireMock.port())).token().expiresAt())
                .isEqualTo(LocalDateTime.ofInstant(T0.plusSeconds(300), ZoneOffset.UTC));

        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"accessToken\":\"opaque\"}")));
        assertThat(client(config(wireMock.port())).token().expiresAt())
                .isEqualTo(LocalDateTime.ofInstant(T0.plusSeconds(600), ZoneOffset.UTC));
    }

    @Test
    void wrongCredential_401_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responseCode\":\"401\",\"message\":\"Invalid credentials\"}")));
        assertThatThrownBy(() -> client(config(wireMock.port())).token())
                .isInstanceOf(StagingClientServiceClient.StagingUnavailableException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("a 2xx without an accessToken is infrastructure, never a token")
    void okWithoutToken_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"responseCode\":\"000\",\"data\":{}}")));
        assertThatThrownBy(() -> client(config(wireMock.port())).token())
                .isInstanceOf(StagingClientServiceClient.StagingUnavailableException.class);
    }

    @Test
    void htmlErrorPageWith200_isUnavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>Request Rejected</html>")));
        assertThatThrownBy(() -> client(config(wireMock.port())).token())
                .isInstanceOf(StagingClientServiceClient.StagingUnavailableException.class);
    }

    @Test
    void connectRefused_isUnavailable() {
        assertThatThrownBy(() -> client(config(1)).token())
                .isInstanceOf(StagingClientServiceClient.StagingUnavailableException.class);
    }

    @Test
    @DisplayName("an unprovisioned credential never reaches the network")
    void unconfigured_makesNoCall() {
        DeviceSecurityProperties.Staging c = config(wireMock.port());
        c.setPassword("");
        StagingClientServiceClient client = client(c);
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(client::token).isInstanceOf(StagingClientServiceClient.StagingUnavailableException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }
}
