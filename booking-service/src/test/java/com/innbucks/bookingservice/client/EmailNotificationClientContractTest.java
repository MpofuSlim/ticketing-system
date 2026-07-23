package com.innbucks.bookingservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.innbucks.bookingservice.config.InnbucksNotifyProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for booking-service's {@link EmailNotificationClient} against the
 * InnBucks public notification API: {@code POST /auth/third-party} for the bearer
 * then {@code POST /api/notification/email}. Pins the OUTBOUND auth headers +
 * plain-text body shape and the 401-refresh-and-replay behaviour, so a wire drift
 * fails the build. A fresh client per test keeps the token cache empty.
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

        client(wireMock.port()).sendEmail("rufaro@example.com", "Your InnBucks tickets",
                "You have 2 tickets", "BOOKING-CONFIRM-1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your InnBucks tickets")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("You have 2 tickets")))
                .withRequestBody(matchingJsonPath("$.destinationEmail", equalTo("rufaro@example.com")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("BOOKING-CONFIRM-1"))));
    }

    @Test
    @DisplayName("subject is transliterated to ASCII: em-dash becomes hyphen (API 400s 'Invalid subject' on typography)")
    void unicodeSubject_isTransliteratedBeforeTheWire() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        // Observed live 2026-07-23: an em-dash in the SUBJECT draws
        // {"status":400,"errors":["Invalid subject"]}; the same text in the
        // BODY is accepted. Subject sanitized, body untouched.
        client(wireMock.port()).sendEmail("rufaro@example.com",
                "Your InnBucks tickets \u2014 booking INN-1",
                "Body keeps typography \u2014 untouched", "REF-1");

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.subject",
                        equalTo("Your InnBucks tickets - booking INN-1")))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Body keeps typography \u2014 untouched"))));
    }

    @Test
    @DisplayName("overlong reference is clamped to 46 chars (API 400s 'Invalid reference' beyond that)")
    void overlongReference_isClampedBeforeTheWire() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        // 52 chars — the exact shape (BOOKING-CONFIRM-<uuid>) the live API
        // rejected with {"status":400,"errors":["Invalid reference"]}.
        String longRef = "BOOKING-CONFIRM-cda7161b-2192-4111-96b5-b295aeb2ff34";

        client(wireMock.port()).sendEmail("rufaro@example.com", "subj", "msg", longRef);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.reference",
                        equalTo(longRef.substring(0, 46)))));
    }

    @Test
    @DisplayName("validation 400 {status,errors[]}: surfaces as NotificationDeliveryException, no retry")
    void validationRejection_throwsWithoutRetry() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        // The envelope observed live for payload-validation failures.
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"timestamp\":\"2026-07-23T07:10:30.676+02:00\",\"status\":400,"
                        + "\"errors\":[\"Invalid reference\",\"Invalid subject\"]}")));

        assertThatThrownBy(() -> client(wireMock.port())
                .sendEmail("rufaro@example.com", "subj", "msg", "REF-1"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 400");
        wireMock.verify(1, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("permission 403 {status,error:Forbidden}: surfaces as NotificationDeliveryException (creds lack email scope)")
    void forbiddenScope_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        // Observed live: valid payload + authenticated token, but the API
        // client has no email-notification permission.
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"timestamp\":\"2026-07-23T08:54:17.515+02:00\",\"status\":403,"
                        + "\"error\":\"Forbidden\",\"path\":\"/api/notification/email\"}")));

        assertThatThrownBy(() -> client(wireMock.port())
                .sendEmail("rufaro@example.com", "subj", "msg", "REF-1"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 403");
        wireMock.verify(1, postRequestedFor(urlEqualTo(EMAIL)));
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

        client(wireMock.port()).sendEmail("rufaro@example.com", "subj", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.reference", matching("TKT-EMAIL-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("401 on email: refresh token and replay once, then succeed")
    void unauthorized_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(EMAIL)));     // first 401 + replay
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));     // initial + forced refresh
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
