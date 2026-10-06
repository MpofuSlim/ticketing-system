package com.innbucks.bookingservice.config;

import feign.Client;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.loadbalancer.config.BlockingLoadBalancerClientAutoConfiguration;
import org.springframework.cloud.loadbalancer.config.LoadBalancerAutoConfiguration;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.FeignLoadBalancerAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound client in booking-service rides the module's ONE pooled
 * transport ({@link PooledHttpClient}) with its own timeouts — the
 * RestClients through {@link PooledHttpClient.PooledRequestFactory}, the
 * {@code @FeignClient}s through Feign's {@link ApacheHttp5Client} over the
 * same {@code CloseableHttpClient}. CLAUDE.md, "Outbound HTTP clients are pooled".
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

        assertPooled(client, 3000, 20000);
        assertThat(userAgentOf(client)).isEqualTo("Java/" + System.getProperty("java.version"));
    }

    @Test
    @DisplayName("WhatsApp gateway: pooled, its own 2s/10s, the User-Agent it always sent")
    void whatsApp() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setBaseUrl("https://wa.example");
        RestClient client = new WhatsAppClientConfig().whatsAppRestClient(props, POOL);

        assertPooled(client, 2000, 10000);
        assertThat(userAgentOf(client)).isEqualTo(PooledHttpClient.URL_CONNECTION_USER_AGENT);
    }

    @Test
    @DisplayName("Feign: the load-balanced client wraps ApacheHttp5Client over the SAME pooled client, and no second pool exists")
    void feignRidesTheSharedPool() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.cloud.discovery.enabled=false")
                .withConfiguration(AutoConfigurations.of(
                        LoadBalancerAutoConfiguration.class,
                        BlockingLoadBalancerClientAutoConfiguration.class,
                        org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration.class,
                        FeignAutoConfiguration.class,
                        FeignLoadBalancerAutoConfiguration.class))
                .withUserConfiguration(PooledHttpClientConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PooledHttpClient pool = context.getBean(PooledHttpClient.class);

                    Client feign = context.getBean(Client.class);
                    assertThat(feign).isInstanceOf(FeignBlockingLoadBalancerClient.class);
                    Client delegate = ((FeignBlockingLoadBalancerClient) feign).getDelegate();
                    assertThat(delegate).isInstanceOf(ApacheHttp5Client.class);
                    assertThat(ReflectionTestUtils.getField(delegate, "client")).isSameAs(pool.httpClient());

                    assertThat(context.getBeansOfType(HttpClientConnectionManager.class))
                            .hasSize(1)
                            .containsValue(pool.connectionManager());
                });
    }
}
