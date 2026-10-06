package com.innbucks.userservice.config;

import com.innbucks.userservice.devicesecurity.DeviceSecurityProperties;
import com.innbucks.userservice.devicesecurity.StagingClientServiceClient;
import com.innbucks.userservice.integration.LoyaltyServiceClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound client in user-service rides the module's ONE pooled
 * transport ({@link PooledHttpClient}) with its own timeouts. Built the way
 * the Spring context builds them. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
class OutboundClientsArePooledTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());

    @AfterAll
    static void close() {
        POOL.close();
    }

    @SuppressWarnings("deprecation") // RequestConfig#getConnectTimeout
    static void assertPooled(RestClient client, long connectMs, long readMs) {
        Object factory = ReflectionTestUtils.getField(client, "clientRequestFactory");
        assertThat(factory).isInstanceOf(PooledHttpClient.PooledRequestFactory.class);
        PooledHttpClient.PooledRequestFactory pooled = (PooledHttpClient.PooledRequestFactory) factory;
        assertThat(pooled.getHttpClient()).isSameAs(POOL.httpClient());
        RequestConfig config = pooled.requestConfig();
        assertThat(config.getConnectTimeout().toMilliseconds()).isEqualTo(connectMs);
        assertThat(config.getResponseTimeout().toMilliseconds()).isEqualTo(readMs);
        assertThat(config.isRedirectsEnabled()).isFalse();
    }

    static String userAgentOf(RestClient client) {
        HttpHeaders headers = (HttpHeaders) ReflectionTestUtils.getField(client, "defaultHeaders");
        return headers == null ? null : headers.getFirst(HttpHeaders.USER_AGENT);
    }

    @Test
    @DisplayName("notification API (email + SMS): pooled, its own 3s/20s, the User-Agent it always sent")
    void notificationApi() {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl("https://notify.example");
        RestClient client = new InnbucksNotifyClientConfig().innbucksNotifyRestClient(props, POOL);

        assertPooled(client, props.getConnectTimeoutMs(), props.getReadTimeoutMs());
        assertThat(userAgentOf(client)).isEqualTo("Java/" + System.getProperty("java.version"));
    }

    @Test
    @DisplayName("WhatsApp gateway: pooled, its own timeouts, the User-Agent it always sent")
    void whatsApp() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setBaseUrl("https://wa.example");
        RestClient client = new WhatsAppClientConfig().whatsAppRestClient(props, POOL);

        assertPooled(client, props.getConnectTimeoutMs(), props.getReadTimeoutMs());
        assertThat(userAgentOf(client)).isEqualTo(PooledHttpClient.URL_CONNECTION_USER_AGENT);
    }

    @Test
    @DisplayName("DTX staging client-service: pooled, its own 3s/15s, the User-Agent it always sent")
    void stagingClientService() {
        DeviceSecurityProperties.Staging staging = new DeviceSecurityProperties.Staging();
        StagingClientServiceClient client = new StagingClientServiceClient(staging, Clock.systemUTC(), POOL);
        RestClient rc = (RestClient) ReflectionTestUtils.getField(client, "restClient");

        assertPooled(rc, 3000, 15000);
        assertThat(userAgentOf(rc)).isEqualTo(PooledHttpClient.URL_CONNECTION_USER_AGENT);
    }

    @Test
    @DisplayName("loyalty-service (load-balanced): pooled with its own timeouts")
    void loyaltyService() {
        LoyaltyServiceClient client = new LoyaltyServiceClient(
                new LoadBalancedRestClientConfig().loadBalancedRestClientBuilder(POOL),
                "http://loyalty-service", 1500, 3500, "token", POOL);

        assertPooled((RestClient) ReflectionTestUtils.getField(client, "http"), 1500, 3500);
    }

    @Test
    @DisplayName("both RestClient builders start on the pool with the defaults, never Spring's default factory")
    void buildersStartPooled() {
        LoadBalancedRestClientConfig config = new LoadBalancedRestClientConfig();
        assertPooled(config.restClientBuilder(POOL).build(), 2000, 10000);
        assertPooled(config.loadBalancedRestClientBuilder(POOL).build(), 2000, 10000);
    }
}
