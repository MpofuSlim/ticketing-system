package com.innbucks.eventservice.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * event-service's one outbound client — the load-balanced {@link RestTemplate}
 * every gateway shares — rides the module's pooled transport
 * ({@link PooledHttpClient}) with its own timeouts. CLAUDE.md, "Outbound HTTP
 * clients are pooled".
 */
class OutboundClientsArePooledTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());

    @AfterAll
    static void close() {
        POOL.close();
    }

    @Test
    @DisplayName("the shared RestTemplate: pooled, its own connect/read timeouts, redirects off")
    @SuppressWarnings("deprecation") // RequestConfig#getConnectTimeout
    void restTemplate() {
        RestTemplate template = new HttpClientConfig().restTemplate(2000, 5000, POOL);

        Object factory = ReflectionTestUtils.getField(template, "requestFactory");
        assertThat(factory).isInstanceOf(PooledHttpClient.PooledRequestFactory.class);
        PooledHttpClient.PooledRequestFactory pooled = (PooledHttpClient.PooledRequestFactory) factory;
        assertThat(pooled.getHttpClient()).isSameAs(POOL.httpClient());
        RequestConfig config = pooled.requestConfig();
        assertThat(config.getConnectTimeout().toMilliseconds()).isEqualTo(2000);
        assertThat(config.getResponseTimeout().toMilliseconds()).isEqualTo(5000);
        assertThat(config.isRedirectsEnabled()).isFalse();
        assertThat(template.getInterceptors()).hasAtLeastOneElementOfType(CorrelationIdPropagatingInterceptor.class);
    }
}
