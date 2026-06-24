package com.innbucks.userservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.innbucks.userservice.config.InnbucksNotifyProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for user-service's {@link EmailNotificationClient} against the
 * InnBucks public notification API: {@code POST /auth/third-party} for the bearer
 * then {@code POST /api/notification/email}. Pins the OUTBOUND auth headers +
 * plain-text body shape and the 401-refresh-and-replay behaviour. A fresh client
 * per test keeps the token cache empty.
 */
class EmailNotificationClientContractTest {

    private static final String LOGIN = "/auth/third-party";
    private static final String EMAIL = "/api/notification/email";
    private static final String API_KEY = "test-api-key";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static EmailNotificationClient client(int port) {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl("http://localhost:" + port);
        props.setApiKey(API_KEY);
        props.setUsername("test-user");
        props.setPassword("test-pass");
        return new EmailNotificationClient(
                RestClient.builder().baseUrl("http://localhost:" + port).build(),
                props, new ObjectMapper());
    }

    @Test
    @DisplayName("happy path: logs in then posts {subject,message,reference,destinationEmail} with X-Api-Key + Bearer")
    void sendEmail_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendEmail("staff@example.com",
                "Welcome to SwiftInn — your account is ready",
                "Temporary password: abc123", "STAFF-ONBOARD-1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Welcome to SwiftInn — your account is ready")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("Temporary password: abc123")))
                .withRequestBody(matchingJsonPath("$.destinationEmail", equalTo("staff@example.com")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("STAFF-ONBOARD-1"))));
    }

    @Test
    @DisplayName("blank recipient/subject/message: guarded before any network call")
    void blankInputs_neverHitTheWire() {
        EmailNotificationClient client = client(wireMock.port());
        assertThatThrownBy(() -> client.sendEmail(" ", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.com", " ", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.com", "s", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("blank reference: auto-fills TKT-EMAIL-<uuid>")
    void blankReference_autoFilled() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendEmail("staff@example.com", "subj", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.reference", matching("TKT-EMAIL-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("401 on email: refresh token and replay once, then succeed")
    void unauthorized_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(EMAIL)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("persistent 401: refresh once then give up with NotificationDeliveryException")
    void unauthorizedTwice_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("login rejected (401): NotificationDeliveryException, email never attempted")
    void loginRejected_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed");
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("500 on email (non-401): NotificationDeliveryException")
    void serverError_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void connectRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> client(closedPort).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }
}
