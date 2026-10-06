package com.innbucks.bookingservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the InnBucks public notification API — email AND SMS. Targets
 * the public API Gateway with bearer + X-Api-Key auth handled in
 * {@code EmailNotificationClient} ({@code POST /api/notification/email} and
 * {@code POST /api/notification/sms}).
 *
 * <p>Rides the module's shared pool ({@link PooledHttpClient}) with this
 * client's own timeouts, and keeps the User-Agent it always sent — the
 * gateway is a partner edge. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
@Configuration
@EnableConfigurationProperties(InnbucksNotifyProperties.class)
public class InnbucksNotifyClientConfig {

    @Bean("innbucksNotifyRestClient")
    public RestClient innbucksNotifyRestClient(InnbucksNotifyProperties properties,
                                               PooledHttpClient pooledHttpClient) {
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(pooledHttpClient.requestFactory(
                        properties.getConnectTimeoutMs(), properties.getReadTimeoutMs()))
                .defaultHeader(HttpHeaders.USER_AGENT, PooledHttpClient.URL_CONNECTION_USER_AGENT)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }
}
