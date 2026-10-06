package com.innbucks.eventservice.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the shared outbound transport's policy (CLAUDE.md, "Outbound HTTP
 * clients are pooled"): one pool, HTTP/1.1, NO automatic retries, no redirect
 * following, no cookie state, per-client timeouts, pool metrics. Pure JUnit +
 * WireMock. Identical in every service module; change them together.
 */
class PooledHttpClientTest {

    private static WireMockServer wireMock;
    private static PooledHttpClient pool;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        pool = new PooledHttpClient(new PooledHttpClientProperties());
    }

    @AfterAll
    static void stop() {
        pool.close();
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                // generous read timeout: a cold WireMock can take seconds to answer its first call
                .requestFactory(pool.requestFactory(2000, 15000))
                .build();
    }

    @Test
    @DisplayName("defaults fit the small cell: 50 total, 20 per route, 1s lease, 2s connect, 10s read")
    void defaults() {
        PooledHttpClientProperties p = new PooledHttpClientProperties();
        assertThat(p.getMaxTotal()).isEqualTo(50);
        assertThat(p.getMaxPerRoute()).isEqualTo(20);
        assertThat(p.getConnectionRequestTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(pool.connectionManager().getMaxTotal()).isEqualTo(50);
        assertThat(pool.connectionManager().getDefaultMaxPerRoute()).isEqualTo(20);
    }

    @Test
    @DisplayName("pool sizes are configurable")
    void sizesAreConfigurable() {
        PooledHttpClientProperties p = new PooledHttpClientProperties();
        p.setMaxTotal(7);
        p.setMaxPerRoute(3);
        try (PooledHttpClient custom = new PooledHttpClient(p)) {
            assertThat(custom.connectionManager().getMaxTotal()).isEqualTo(7);
            assertThat(custom.connectionManager().getDefaultMaxPerRoute()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("every factory shares the ONE client and carries its own connect/read timeouts")
    @SuppressWarnings("deprecation") // RequestConfig#getConnectTimeout
    void factoriesShareTheClientWithTheirOwnTimeouts() {
        PooledHttpClient.PooledRequestFactory a = pool.requestFactory(300, 700);
        PooledHttpClient.PooledRequestFactory b = pool.requestFactory(Duration.ofSeconds(3), Duration.ofSeconds(20));

        assertThat(a.getHttpClient()).isSameAs(pool.httpClient()).isSameAs(b.getHttpClient());
        RequestConfig ca = a.requestConfig();
        assertThat(ca.getConnectTimeout().toMilliseconds()).isEqualTo(300);
        assertThat(ca.getResponseTimeout().toMilliseconds()).isEqualTo(700);
        assertThat(ca.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(1000);
        assertThat(ca.isRedirectsEnabled()).isFalse();
        RequestConfig cb = b.requestConfig();
        assertThat(cb.getConnectTimeout().toMilliseconds()).isEqualTo(3000);
        assertThat(cb.getResponseTimeout().toMilliseconds()).isEqualTo(20000);

        RequestConfig defaults = pool.requestFactory().requestConfig();
        assertThat(defaults.getConnectTimeout().toMilliseconds()).isEqualTo(2000);
        assertThat(defaults.getResponseTimeout().toMilliseconds()).isEqualTo(10000);
    }

    @Test
    @DisplayName("NO automatic retries: an I/O failure on a GET is sent exactly once")
    void noAutomaticRetries_get() {
        wireMock.stubFor(get(urlEqualTo("/x")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client().get().uri("/x").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        wireMock.verify(1, getRequestedFor(urlEqualTo("/x")));
    }

    @Test
    @DisplayName("NO automatic retries: a POST that dies mid-response is sent exactly once (never a second charge)")
    void noAutomaticRetries_post() {
        wireMock.stubFor(post(urlEqualTo("/charge")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThatThrownBy(() -> client().post().uri("/charge").body("{}").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/charge")));
    }

    @Test
    @DisplayName("HTTP/1.1 on the wire, no Accept-Encoding added, connection reused from the pool")
    void http11_andReused() {
        wireMock.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(200).withBody("ok")));

        RestClient c = client();
        c.get().uri("/a").retrieve().toBodilessEntity();
        c.get().uri("/a").retrieve().toBodilessEntity();

        List<LoggedRequest> seen = wireMock.findAll(getRequestedFor(urlEqualTo("/a")));
        assertThat(seen).hasSize(2).allSatisfy(r -> {
            assertThat(r.getProtocol()).isEqualTo("HTTP/1.1");
            assertThat(r.containsHeader(HttpHeaders.ACCEPT_ENCODING)).isFalse();
        });
        assertThat(pool.connectionManager().getTotalStats().getLeased()).isZero();
        assertThat(pool.connectionManager().getTotalStats().getAvailable()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a redirect is returned, never followed (it would re-send credential headers elsewhere)")
    void redirectsAreNotFollowed() {
        wireMock.stubFor(get(urlEqualTo("/from")).willReturn(aResponse().withStatus(302)
                .withHeader(HttpHeaders.LOCATION, "http://localhost:" + wireMock.port() + "/to")));

        ResponseEntity<Void> response = client().get().uri("/from")
                .header("X-Api-Key", "secret").retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        wireMock.verify(0, getRequestedFor(urlEqualTo("/to")));
    }

    @Test
    @DisplayName("no cookie state: a Set-Cookie from one call is never replayed on the next")
    void noCookieState() {
        wireMock.stubFor(get(urlEqualTo("/c")).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.SET_COOKIE, "__cf_bm=abc; Path=/")));

        RestClient c = client();
        c.get().uri("/c").retrieve().toBodilessEntity();
        c.get().uri("/c").retrieve().toBodilessEntity();

        assertThat(wireMock.findAll(anyRequestedFor(anyUrl())))
                .allSatisfy(r -> assertThat(r.containsHeader(HttpHeaders.COOKIE)).isFalse());
    }

    @Test
    @DisplayName("a closed port fails fast as a ResourceAccessException")
    void connectRefused() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        RestClient dead = RestClient.builder().baseUrl("http://localhost:" + closedPort)
                .requestFactory(pool.requestFactory(500, 500)).build();

        assertThatThrownBy(() -> dead.get().uri("/").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("pool metrics: max / leased / available / pending, tagged httpclient=outbound")
    void poolMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PooledHttpClientConfig().pooledHttpClientMetrics(pool).bindTo(registry);

        Gauge max = registry.find("httpcomponents.httpclient.pool.total.max")
                .tag("httpclient", PooledHttpClientConfig.POOL_NAME).gauge();
        assertThat(max).isNotNull();
        assertThat(max.value()).isEqualTo(50.0);
        assertThat(registry.find("httpcomponents.httpclient.pool.total.connections").tag("state", "leased").gauge())
                .isNotNull();
        assertThat(registry.find("httpcomponents.httpclient.pool.total.connections").tag("state", "available").gauge())
                .isNotNull();
        assertThat(registry.find("httpcomponents.httpclient.pool.total.pending").gauge()).isNotNull();
    }
}
