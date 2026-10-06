package com.innbucks.apigateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway is the ONE place responses are compressed, and the place every
 * trace starts (CLAUDE.md "Tracing and compression"). Drives real requests
 * through a running gateway to a stand-in backend, so what is pinned is the
 * wire: Content-Encoding to the client, traceparent to the service.
 *
 * <p>The client is the JDK HttpClient on purpose: it never decompresses on
 * its own, so a gzip'd body arrives exactly as the gateway sent it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayCompressionAndTracingTest {

    private static final Pattern TRACEPARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    /** Last request headers the backend saw, per path. */
    private static final Map<String, Headers> SEEN = new ConcurrentHashMap<>();

    private static final String LARGE_JSON = json(4000);
    private static final String SMALL_JSON = json(200);
    private static final byte[] PNG = new byte[8000];

    private static final HttpServer BACKEND = startBackend();

    @TestConfiguration
    static class BackendRoute {
        @Bean
        RouteLocator compressionTestRoutes(RouteLocatorBuilder routes) {
            String uri = "http://localhost:" + BACKEND.getAddress().getPort();
            return routes.routes()
                    .route("compression-test-route", r -> r.order(-1).path("/__compression-test/**").uri(uri))
                    .build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        SEEN.clear();
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    // ---------------------------------------------------------------- compression

    @Test
    void largeJson_isGzippedAtTheGateway() throws Exception {
        HttpResponse<byte[]> response = get("/__compression-test/large.json", "gzip");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(response.body().length).isLessThan(LARGE_JSON.length());
        assertThat(gunzip(response.body())).isEqualTo(LARGE_JSON);
        assertThat(vary(response)).contains("Accept-Encoding")
                // the CORS values the gateway already sends are kept
                .contains("Origin");
    }

    @Test
    void smallJson_isNotCompressed_andKeepsItsLength() throws Exception {
        HttpResponse<byte[]> response = get("/__compression-test/small.json", "gzip");

        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(SMALL_JSON);
        assertThat(response.headers().firstValue("Content-Length")).hasValue(String.valueOf(SMALL_JSON.length()));
        // The NEXT body at this URL may be large enough to compress, and a
        // cache keys on the header, not the size.
        assertThat(vary(response)).contains("Accept-Encoding");
    }

    @Test
    void anImage_isNeverCompressed_andKeepsItsContentLength() throws Exception {
        HttpResponse<byte[]> response = get("/__compression-test/banner.png", "gzip");

        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(response.headers().firstValue("Content-Length")).hasValue(String.valueOf(PNG.length));
        assertThat(response.body()).hasSize(PNG.length);
        assertThat(vary(response)).doesNotContain("Accept-Encoding");
    }

    @Test
    void aClientThatDoesNotAcceptGzip_getsThePlainBody() throws Exception {
        HttpResponse<byte[]> response = get("/__compression-test/large.json", null);

        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(LARGE_JSON);
    }

    // ---------------------------------------------------------------- tracing

    @Test
    void theServiceBehindTheGateway_receivesATraceparent() throws Exception {
        get("/__compression-test/small.json", null);

        String traceparent = SEEN.get("/__compression-test/small.json").getFirst("traceparent");
        assertThat(traceparent).isNotNull().matches(TRACEPARENT);
    }

    @Test
    void aCallersTraceparent_isReplaced_notContinued() throws Exception {
        String forgedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String forged = "00-" + forgedTraceId + "-00f067aa0ba902b7-01";

        send(HttpRequest.newBuilder(uri("/__compression-test/small.json"))
                .header("traceparent", forged).GET().build());

        String seen = SEEN.get("/__compression-test/small.json").getFirst("traceparent");
        assertThat(seen).isNotNull().matches(TRACEPARENT).isNotEqualTo(forged);
        assertThat(seen).doesNotContain(forgedTraceId);
    }

    @Test
    void theCorrelationId_isUntouched_alongsideTheTrace() throws Exception {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/__compression-test/small.json"))
                .header("X-Correlation-Id", "corr-123").GET().build());

        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("corr-123");
        Headers seen = SEEN.get("/__compression-test/small.json");
        assertThat(seen.getFirst("X-Correlation-Id")).isEqualTo("corr-123");
        assertThat(seen.getFirst("traceparent")).isNotNull();
    }

    @Test
    void withNoExportEndpoint_thereIsATracer_butNoExporter() {
        assertThat(context.getBeansOfType(Tracer.class)).isNotEmpty();
        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static String vary(HttpResponse<?> response) {
        return String.join(",", response.headers().allValues("Vary"));
    }

    private HttpResponse<byte[]> get(String path, String acceptEncoding) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (acceptEncoding != null) {
            request.header("Accept-Encoding", acceptEncoding);
        }
        return send(request.build());
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String gunzip(byte[] body) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String json(int approxLength) {
        StringBuilder items = new StringBuilder();
        int i = 0;
        while (items.length() < approxLength) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"id\":").append(i++).append(",\"name\":\"Harare Fun Run\"}");
        }
        return "{\"code\":\"200 OK\",\"message\":\"ok\",\"data\":[" + items + "]}";
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/__compression-test/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                SEEN.put(path, exchange.getRequestHeaders());
                byte[] body;
                String type;
                if (path.endsWith("large.json")) {
                    body = LARGE_JSON.getBytes(StandardCharsets.UTF_8);
                    type = "application/json";
                } else if (path.endsWith(".png")) {
                    body = PNG;
                    type = "image/png";
                } else {
                    body = SMALL_JSON.getBytes(StandardCharsets.UTF_8);
                    type = "application/json";
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
