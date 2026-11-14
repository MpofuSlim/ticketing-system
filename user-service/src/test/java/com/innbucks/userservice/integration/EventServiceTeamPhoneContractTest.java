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

import java.net.ServerSocket;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link EventServiceClient#onAGateTeam}, the STRICT read of
 * event-service's {@code POST /events/internal/team-phone} (V57). A gate
 * sign-in code is sent only on a yes, so "could not ask" must never read as an
 * answer: only a 2xx carrying {@code data.onTeam} is one. The number rides the
 * body, never the URL. A wrong token is event-service's 401 envelope.
 */
class EventServiceTeamPhoneContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String TOKEN = "the-shared-secret";
    private static final String PHONE = "+263771234567";
    private static final String PATH = "/events/internal/team-phone";

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

    private EventServiceClient client() {
        return client("http://localhost:" + wireMock.port(), TOKEN);
    }

    private static EventServiceClient client(String baseUrl, String token) {
        return new EventServiceClient(RestClient.builder(), baseUrl, 2000, 3000, token, POOL);
    }

    @Test
    @DisplayName("200 onTeam true: yes, and the wire carries the token and the number in a JSON body")
    void onTeam() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Team phone checked\",\"data\":{\"onTeam\":true}}")));

        assertThat(client().onAGateTeam(PHONE)).isEqualTo(Optional.of(true));

        wireMock.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.phoneNumber", equalTo(PHONE))));
    }

    @Test
    @DisplayName("200 onTeam false: no")
    void notOnTeam() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Team phone checked\",\"data\":{\"onTeam\":false}}")));

        assertThat(client().onAGateTeam(PHONE)).isEqualTo(Optional.of(false));
    }

    @Test
    @DisplayName("401, 500, a body without the answer, and a non-JSON body: unknown, never no")
    void unanswered() {
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Token\",\"data\":null}")));
        assertThat(client().onAGateTeam(PHONE)).isEmpty();

        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(client().onAGateTeam(PHONE)).isEmpty();

        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Team phone checked\",\"data\":null}")));
        assertThat(client().onAGateTeam(PHONE)).isEmpty();

        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Team phone checked\",\"data\":{}}")));
        assertThat(client().onAGateTeam(PHONE)).isEmpty();

        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>gateway</html>")));
        assertThat(client().onAGateTeam(PHONE)).isEmpty();
    }

    @Test
    @DisplayName("connection refused: unknown")
    void connectionRefused() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertThat(client("http://localhost:" + closed, TOKEN).onAGateTeam(PHONE)).isEmpty();
    }

    @Test
    @DisplayName("guard rails: no token and no number are unknown, without a call")
    void guardRails() {
        assertThat(client("http://localhost:" + wireMock.port(), " ").onAGateTeam(PHONE)).isEmpty();
        assertThat(client().onAGateTeam(" ")).isEmpty();
        assertThat(client().onAGateTeam(null)).isEmpty();

        wireMock.verify(0, postRequestedFor(urlPathMatching("/events/.*")));
    }
}
