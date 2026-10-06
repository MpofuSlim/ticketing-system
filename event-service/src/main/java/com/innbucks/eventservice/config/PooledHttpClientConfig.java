package com.innbucks.eventservice.config;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
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
}
