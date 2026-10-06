package com.innbucks.eventservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    // @LoadBalanced: the BookingGateway / SeatCategoryGateway issue calls to
    // http://booking-service / http://seat-service; the load balancer resolves
    // those names against the static discovery map. Both targets are ticketing
    // siblings.
    //
    // The transport is the module's shared pool (PooledHttpClient) with this
    // template's own timeouts — never SimpleClientHttpRequestFactory or
    // Spring's default. CLAUDE.md, "Outbound HTTP clients are pooled".
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate(
            @Value("${http.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${http.client.read-timeout-ms:5000}") int readTimeoutMs,
            PooledHttpClient pooledHttpClient) {
        RestTemplate template = new RestTemplate(pooledHttpClient.requestFactory(connectTimeoutMs, readTimeoutMs));
        template.getInterceptors().add(new CorrelationIdPropagatingInterceptor());
        return template;
    }
}
