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
import java.util.List;
import java.util.OptionalLong;
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
 * Contract test for {@link EventServiceClient#liveEventCount}, the STRICT read
 * of event-service's {@code GET /events/internal/live-event-count} (V53). A
 * ticketing suspension is refused while the answer is above zero, so "could
 * not ask" must never read as zero: only a 2xx carrying {@code data.liveEvents}
 * is an answer. The body is event-service's {@code ApiResult} envelope; a wrong
 * token is a 401 with the same envelope.
 */
class EventServiceLiveEventCountContractTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final String TOKEN = "the-shared-secret";
    private static final UUID A = UUID.fromString("8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e");
    private static final UUID B = UUID.fromString("5fc4c9d2-7b4f-4d12-a1c3-9e2f0bda1d3e");
    private static final String PATH = "/events/internal/live-event-count";

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
    @DisplayName("200 with a count: answered, and the wire carries the token and every organizer")
    void answered() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Live events counted\",\"data\":{\"liveEvents\":2}}")));

        assertThat(client().liveEventCount(List.of(A, B))).isEqualTo(OptionalLong.of(2));

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withQueryParam("organizerUuids", equalTo(A + "," + B)));
    }

    @Test
    @DisplayName("200 with zero: answered zero")
    void zero() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Live events counted\",\"data\":{\"liveEvents\":0}}")));

        assertThat(client().liveEventCount(List.of(A))).isEqualTo(OptionalLong.of(0));
    }

    @Test
    @DisplayName("401, 500, a body without the count, and a non-JSON body: unknown, never zero")
    void unanswered() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"401 UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Token\",\"data\":null}")));
        assertThat(client().liveEventCount(List.of(A))).isEmpty();

        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        assertThat(client().liveEventCount(List.of(A))).isEmpty();

        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"code\":\"200 OK\",\"message\":\"Live events counted\",\"data\":null}")));
        assertThat(client().liveEventCount(List.of(A))).isEmpty();

        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>gateway</html>")));
        assertThat(client().liveEventCount(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("connection refused: unknown")
    void connectionRefused() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertThat(client("http://localhost:" + closed, TOKEN).liveEventCount(List.of(A))).isEmpty();
    }

    @Test
    @DisplayName("guard rails: no organizers is zero and no token is unknown, both without a call")
    void guardRails() {
        assertThat(client().liveEventCount(List.of())).isEqualTo(OptionalLong.of(0));
        assertThat(client("http://localhost:" + wireMock.port(), " ").liveEventCount(List.of(A))).isEmpty();

        wireMock.verify(0, getRequestedFor(urlPathMatching("/events/.*")));
    }
}
