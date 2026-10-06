package com.innbucks.seatservice.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.time.Duration;

/**
 * This service's ONE outbound HTTP client: an Apache HttpClient 5 (classic, so
 * always HTTP/1.1) over a single {@link PoolingHttpClientConnectionManager},
 * shared by every outbound client in the module. CLAUDE.md, "Outbound HTTP
 * clients are pooled".
 *
 * <p>Before it, each client built its own transport — {@code
 * SimpleClientHttpRequestFactory} (HttpURLConnection), a private JDK
 * {@code HttpClient} (which negotiates HTTP/2), or Feign's default — with no pool
 * limit, no lease timeout, and in places a new TLS handshake per call.
 *
 * <p>What every client gets from here, and why each is deliberate:
 * <ul>
 *   <li><b>No automatic retries.</b> HttpClient's default retry strategy
 *       re-sends some requests after an I/O error. Several calls here must
 *       never be sent twice (a payment code, a checkout, a charge); a client
 *       that wants retries already has its own Resilience4j wrapper.</li>
 *   <li><b>No redirect following.</b> A redirect re-sends the request's
 *       headers — {@code X-Api-Key}, {@code X-Internal-Token}, a bearer — to
 *       whatever host the {@code Location} names. A 3xx comes back as a
 *       response instead.</li>
 *   <li><b>No cookie store and no content compression.</b> The client is
 *       shared across partners, so it must carry no state between them, and
 *       the request headers stay what they were before the swap.</li>
 *   <li><b>System proxy and TLS settings</b> ({@code ProxySelector.getDefault()},
 *       {@code javax.net.ssl.*}), the same sources the JDK transports read.</li>
 *   <li><b>Bounded everything</b>: lease, connect, TLS handshake, response.</li>
 * </ul>
 *
 * <p>A client gets its own timeouts through {@link #requestFactory(Duration, Duration)},
 * applied per request; the pool and the connections stay shared. Never build a
 * transport per call, and never fall back to Spring's default request factory.
 */
public final class PooledHttpClient implements AutoCloseable {

    /**
     * The User-Agent the HttpURLConnection-based clients sent before the
     * switch. Partner edges allow-list User-Agents (EcoCash refused the JDK
     * default outright — CLAUDE.md, the EcoCash section), so a client talking
     * to a partner keeps the exact value it always sent rather than starting to
     * announce itself as Apache HttpClient.
     */
    public static final String URL_CONNECTION_USER_AGENT = "Java/" + System.getProperty("java.version");

    /** The User-Agent the JDK-{@code HttpClient}-based clients sent before the switch. */
    public static final String JDK_HTTP_CLIENT_USER_AGENT = "Java-http-client/" + System.getProperty("java.version");

    private final PooledHttpClientProperties properties;
    private final PoolingHttpClientConnectionManager connectionManager;
    private final CloseableHttpClient httpClient;

    public PooledHttpClient(PooledHttpClientProperties properties) {
        this.properties = properties;
        this.connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.getMaxTotal())
                .setMaxConnPerRoute(properties.getMaxPerRoute())
                .setTlsSocketStrategy(DefaultClientTlsStrategy.createSystemDefault())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(properties.getConnectTimeout()))
                        .setSocketTimeout(timeout(properties.getReadTimeout()))
                        .setValidateAfterInactivity(timeValue(properties.getValidateAfterInactivity()))
                        .setTimeToLive(timeValue(properties.getTimeToLive()))
                        .build())
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(timeout(properties.getReadTimeout()))
                        .build())
                // The TLS handshake runs before any per-request timeout applies, so
                // it gets the pool-wide read timeout — the same bound the socket
                // read timeout gave it under HttpURLConnection.
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(timeout(properties.getReadTimeout()))
                        .build())
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setRoutePlanner(new SystemDefaultRoutePlanner(null))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout(properties.getConnectionRequestTimeout()))
                        .setResponseTimeout(timeout(properties.getReadTimeout()))
                        .setRedirectsEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .evictExpiredConnections()
                .evictIdleConnections(timeValue(properties.getIdleEviction()))
                .build();
    }

    /** The shared client (booking-service's Feign clients wrap this same instance). */
    public CloseableHttpClient httpClient() {
        return httpClient;
    }

    /** The shared pool, for metrics. */
    public PoolingHttpClientConnectionManager connectionManager() {
        return connectionManager;
    }

    /** A request factory on the shared pool with the service-wide default timeouts. */
    public PooledRequestFactory requestFactory() {
        return requestFactory(properties.getConnectTimeout(), properties.getReadTimeout());
    }

    /**
     * A request factory on the shared pool with THIS client's connect and read
     * timeouts, applied per request. Cheap — it holds no connections of its own.
     */
    public PooledRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return new PooledRequestFactory(httpClient, connectTimeout, readTimeout,
                properties.getConnectionRequestTimeout());
    }

    /** Millisecond convenience for the {@code *-timeout-ms} properties most clients carry. */
    public PooledRequestFactory requestFactory(long connectTimeoutMs, long readTimeoutMs) {
        return requestFactory(Duration.ofMillis(connectTimeoutMs), Duration.ofMillis(readTimeoutMs));
    }

    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    private static Timeout timeout(Duration d) {
        return Timeout.ofMilliseconds(d.toMillis());
    }

    private static TimeValue timeValue(Duration d) {
        return TimeValue.ofMilliseconds(d.toMillis());
    }

    /**
     * Spring's HttpComponents factory with a per-client connect timeout. Spring 7
     * dropped {@code setConnectTimeout} (HttpClient moved it to the pool's
     * {@code ConnectionConfig}), but the request-level value still takes
     * precedence when a NEW connection is opened, which is what lets clients
     * with different connect timeouts share one pool.
     *
     * <p>Never registered as a bean: Spring's {@code destroy()} would close the
     * shared client under every other user.
     */
    public static final class PooledRequestFactory extends HttpComponentsClientHttpRequestFactory {

        private final Duration connectTimeout;
        private final Duration readTimeout;
        private final Duration connectionRequestTimeout;

        PooledRequestFactory(CloseableHttpClient httpClient, Duration connectTimeout, Duration readTimeout,
                             Duration connectionRequestTimeout) {
            super(httpClient);
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.connectionRequestTimeout = connectionRequestTimeout;
            setReadTimeout(readTimeout);
            setConnectionRequestTimeout(connectionRequestTimeout);
        }

        @Override
        @SuppressWarnings("deprecation") // RequestConfig#setConnectTimeout: still honoured per request
        protected RequestConfig createRequestConfig(Object client) {
            RequestConfig base = super.createRequestConfig(client);
            return (base == null ? RequestConfig.custom() : RequestConfig.copy(base))
                    .setConnectTimeout(timeout(connectTimeout))
                    .setResponseTimeout(timeout(readTimeout))
                    .setConnectionRequestTimeout(timeout(connectionRequestTimeout))
                    .setRedirectsEnabled(false)
                    .build();
        }

        /** The exact config every request from this factory carries — for tests. */
        public RequestConfig requestConfig() {
            return createRequestConfig(getHttpClient());
        }

        /** Never close the shared client from a factory. */
        @Override
        public void destroy() {
            // the pool belongs to PooledHttpClient, which the context closes once
        }
    }
}
