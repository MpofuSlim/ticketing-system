package innbucks.paymentservice.config;

import innbucks.paymentservice.client.PaynowProperties;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * The module's one pooled outbound HTTP client ({@link PooledHttpClient}) and
 * its pool metrics. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PooledHttpClientProperties.class)
public class PooledHttpClientConfig {

    /** Metric pool name: {@code httpcomponents.httpclient.pool.*{httpclient="outbound"}}. */
    static final String POOL_NAME = "outbound";

    /**
     * Paynow's hosts are offered the JVM's own cipher list: Paynow accepts only
     * CBC ciphers, which HttpClient's default list drops ({@link TlsCiphersByHost}).
     * Every other host keeps HttpClient's default.
     */
    @Bean(destroyMethod = "close")
    public PooledHttpClient pooledHttpClient(PooledHttpClientProperties properties,
                                             ObjectProvider<PaynowProperties> paynow) {
        PaynowProperties p = paynow.getIfAvailable();
        PooledHttpClient client = new PooledHttpClient(properties, p == null ? Set.of() : p.hosts());
        if (!client.tls().jvmCipherHosts().isEmpty()) {
            log.info("Outbound TLS with the JVM's cipher list, CBC included, for {} (their server has no GCM cipher)",
                    client.tls().jvmCipherHosts());
        }
        return client;
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
