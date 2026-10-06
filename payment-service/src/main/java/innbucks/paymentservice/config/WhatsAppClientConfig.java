package innbucks.paymentservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the WhatsApp gateway. Rides the module's shared pool
 * ({@link PooledHttpClient}) with this client's own timeouts, and keeps the
 * User-Agent it always sent — the gateway is a partner edge. CLAUDE.md,
 * "Outbound HTTP clients are pooled".
 */
@Configuration
@EnableConfigurationProperties(WhatsAppProperties.class)
public class WhatsAppClientConfig {

    @Bean("whatsAppRestClient")
    public RestClient whatsAppRestClient(WhatsAppProperties properties, PooledHttpClient pooledHttpClient) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(pooledHttpClient.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .defaultHeader(HttpHeaders.USER_AGENT, PooledHttpClient.URL_CONNECTION_USER_AGENT)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }
}
