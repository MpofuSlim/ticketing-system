package com.innbucks.seatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound client in seat-service rides the module's ONE pooled
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

    static RestClient restClientOf(Object client) {
        return (RestClient) ReflectionTestUtils.getField(client, "restClient");
    }

    @Test
    @DisplayName("event-service + booking-service (load-balanced): pooled, each with its own timeouts")
    void siblings() {
        LoadBalancedRestClientConfig config = new LoadBalancedRestClientConfig();
        assertPooled(restClientOf(new EventServiceClient(config.loadBalancedRestClientBuilder(POOL),
                "http://event-service", 2001, 5001, new ObjectMapper(), POOL)), 2001, 5001);
        assertPooled(restClientOf(new BookingServiceClient(config.loadBalancedRestClientBuilder(POOL),
                "http://booking-service", 2002, 5002, "t", new ObjectMapper(), POOL)), 2002, 5002);
    }

    @Test
    @DisplayName("both RestClient builders start on the pool with the defaults, never Spring's default factory")
    void buildersStartPooled() {
        LoadBalancedRestClientConfig config = new LoadBalancedRestClientConfig();
        assertPooled(config.restClientBuilder(POOL).build(), 2000, 10000);
        assertPooled(config.loadBalancedRestClientBuilder(POOL).build(), 2000, 10000);
    }
}
