package com.innbucks.bookingservice.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The module's one pooled outbound HTTP client ({@link PooledHttpClient}) and
 * its pool metrics. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PooledHttpClientProperties.class)
public class PooledHttpClientConfig {

    /** Metric pool name: {@code httpcomponents.httpclient.pool.*{httpclient="outbound"}}. */
    static final String POOL_NAME = "outbound";

    @Bean(destroyMethod = "close")
    public PooledHttpClient pooledHttpClient(PooledHttpClientProperties properties) {
        return new PooledHttpClient(properties);
    }

    /**
     * Leased / available / pending / max for the shared pool. A sustained
     * {@code pending > 0} means callers are queueing for a connection — the pool
     * is too small or a peer is slow.
     */
    @Bean
    public MeterBinder pooledHttpClientMetrics(PooledHttpClient pooledHttpClient) {
        return new PoolingHttpClientConnectionManagerMetricsBinder(
                pooledHttpClient.connectionManager(), POOL_NAME);
    }

    /**
     * Exposed so Spring Cloud OpenFeign's Apache HC5 support wraps THIS client
     * (its own {@code HttpClient5FeignConfiguration} backs off when a
     * {@link CloseableHttpClient} bean exists) instead of building a second
     * pool. {@code destroyMethod = ""}: {@link PooledHttpClient} owns its
     * lifecycle and closes it once.
     */
    @Bean(destroyMethod = "")
    public CloseableHttpClient outboundHttpClient(PooledHttpClient pooledHttpClient) {
        return pooledHttpClient.httpClient();
    }

    /** Same reason, for the pool bean Feign's configuration would otherwise add. */
    @Bean(destroyMethod = "")
    public HttpClientConnectionManager outboundHttpClientConnectionManager(PooledHttpClient pooledHttpClient) {
        return pooledHttpClient.connectionManager();
    }
}
