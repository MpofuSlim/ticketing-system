package com.innbucks.apigateway;

import com.innbucks.apigateway.config.ClientIpResolver;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate-limit keys a real request gets through the running gateway: Reactor
 * Netty, the {@code framework} forward-headers transformer from application.yaml,
 * and the production key-resolver beans. A test route answers with the key each
 * IP-keyed resolver produced, so what is pinned is the key the limiter would
 * charge, for the header shapes the edge can deliver. {@code ClientIpResolverTest}
 * covers the parsing cases one by one.
 *
 * <p>A second route proxies to a stand-in backend, pinning what the services
 * behind the gateway receive: nginx's {@code X-Real-IP} unchanged, and no
 * {@code X-Forwarded-For} or {@code Forwarded} (the caller-chosen chain).
 * Setting {@code trusted-proxies} would make the gateway forward an XFF built
 * from the caller-chosen remote address, and this is the test that says so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayClientIpTest {

    private static final String DEFAULT_KEY = "X-Key-Default";
    private static final String PRE_AUTH_KEY = "X-Key-Pre-Auth";
    private static final String PAYMENTS_KEY = "X-Key-Payments";

    /** The request headers the stand-in backend last received. */
    private static final AtomicReference<Headers> BACKEND_SAW = new AtomicReference<>();

    private static final HttpServer BACKEND = startBackend();

    @TestConfiguration
    static class KeyEchoRoute {
        @Bean
        RouteLocator clientIpTestRoutes(RouteLocatorBuilder routes,
                                        @Qualifier("gatewayKeyResolver") KeyResolver defaultKeys,
                                        @Qualifier("preAuthIpKeyResolver") KeyResolver preAuthKeys,
                                        @Qualifier("paymentsInnbucksIpKeyResolver") KeyResolver paymentKeys) {
            // Answers itself: nothing is forwarded, so the uri is never called.
            return routes.routes()
                    .route("client-ip-test-route", r -> r.order(-1).path("/__client-ip-test/**")
                            .filters(f -> f.filter((exchange, chain) -> defaultKeys.resolve(exchange)
                                    .zipWith(preAuthKeys.resolve(exchange))
                                    .zipWith(paymentKeys.resolve(exchange))
                                    .flatMap(keys -> {
                                        HttpHeaders headers = exchange.getResponse().getHeaders();
                                        headers.set(DEFAULT_KEY, keys.getT1().getT1());
                                        headers.set(PRE_AUTH_KEY, keys.getT1().getT2());
                                        headers.set(PAYMENTS_KEY, keys.getT2());
                                        return exchange.getResponse().setComplete();
                                    })))
                            .uri("http://localhost:9"))
                    .route("client-ip-proxy-test-route", r -> r.order(-1).path("/__client-ip-proxy/**")
                            .uri("http://localhost:" + BACKEND.getAddress().getPort()))
                    .build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry meterRegistry;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @Test
    void aSpoofedLeftMostXff_withTheSameRealIp_keepsEveryKey() throws Exception {
        List<String> first = keys("203.0.113.7", null);
        List<String> spoofed = keys("203.0.113.7", "6.6.6.6, 172.70.1.1");
        List<String> spoofedAgain = keys("203.0.113.7", "7.7.7.7, 8.8.8.8, 172.70.1.1");

        assertThat(first).containsOnly("ip:203.0.113.7");
        assertThat(spoofed).isEqualTo(first);
        assertThat(spoofedAgain).isEqualTo(first);
    }

    @Test
    void aDifferentRealIp_changesEveryKey() throws Exception {
        assertThat(keys("203.0.113.7", "6.6.6.6")).containsOnly("ip:203.0.113.7");
        assertThat(keys("198.51.100.20", "6.6.6.6")).containsOnly("ip:198.51.100.20");
    }

    @Test
    void aHostnameRealIp_isNeverLookedUp_andXffNeverBecomesAKey() throws Exception {
        // A lookup would key ip:127.0.0.1; trusting XFF would key ip:6.6.6.6.
        // Both are wrong: each resolver's shared fallback bucket is right.
        assertThat(keys("localhost", "6.6.6.6"))
                .containsExactly("anonymous", "anonymous-pre-auth", "anonymous-payments-innbucks");
    }

    @Test
    void noRealIp_andNoProxy_keysOnTheSocketPeer() throws Exception {
        // Local dev, or a curl to NodePort 30080 from the box.
        assertThat(keys(null, null)).containsOnly("ip:127.0.0.1");
    }

    @Test
    void anIpv6Client_isKeyedByItsSlash64() throws Exception {
        assertThat(keys("2001:db8:1:2::1", "6.6.6.6")).containsOnly("ip:2001:db8:1:2:0:0:0:0/64");
        assertThat(keys("2001:db8:1:2:a1b2:c3d4:e5f6:789", null)).containsOnly("ip:2001:db8:1:2:0:0:0:0/64");
        assertThat(keys("2001:db8:1:3::1", null)).containsOnly("ip:2001:db8:1:3:0:0:0:0/64");
    }

    @Test
    void theSameRealIpSentTwice_isOneClient() throws Exception {
        // nginx with `include proxy_params` plus its own X-Real-IP line sends
        // two header lines with one value.
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/__client-ip-test/otp"))
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7")
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7")
                .GET().build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.headers().firstValue(PRE_AUTH_KEY)).hasValue("ip:203.0.113.7");
        assertThat(response.headers().firstValue(DEFAULT_KEY)).hasValue("ip:203.0.113.7");
    }

    @Test
    void aBackend_receivesTheRealIpUnchanged_andNoForwardedChain() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/__client-ip-proxy/bookings"))
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7")
                .header("X-Forwarded-For", "6.6.6.6, 172.70.1.1")
                .header("Forwarded", "for=6.6.6.6")
                .GET().build();

        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        Headers seen = BACKEND_SAW.get();
        assertThat(seen.get(ClientIpResolver.CLIENT_IP_HEADER)).containsExactly("203.0.113.7");
        assertThat(seen.containsKey("X-Forwarded-For")).isFalse();
        assertThat(seen.containsKey("Forwarded")).isFalse();
    }

    @Test
    void theFallbackCounters_areRegisteredAtStartup() {
        assertThat(meterRegistry.find("gateway.client_ip.fallback").tag("reason", "absent").counter()).isNotNull();
        assertThat(meterRegistry.find("gateway.client_ip.fallback").tag("reason", "invalid").counter()).isNotNull();
    }

    /** The default, pre-auth and payments keys, in that order. */
    private List<String> keys(String realIp, String xff) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/__client-ip-test/otp")).GET();
        if (realIp != null) {
            request.header(ClientIpResolver.CLIENT_IP_HEADER, realIp);
        }
        if (xff != null) {
            request.header("X-Forwarded-For", xff);
        }
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return List.of(
                response.headers().firstValue(DEFAULT_KEY).orElseThrow(),
                response.headers().firstValue(PRE_AUTH_KEY).orElseThrow(),
                response.headers().firstValue(PAYMENTS_KEY).orElseThrow());
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/__client-ip-proxy/", exchange -> {
                BACKEND_SAW.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
