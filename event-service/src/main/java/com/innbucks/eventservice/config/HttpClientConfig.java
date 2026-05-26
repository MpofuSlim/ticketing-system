package com.innbucks.eventservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

    // @LoadBalanced: the BookingGateway / SeatCategoryGateway issue calls to
    // http://booking-service / http://seat-service; the load balancer resolves
    // those names against the Eureka registry. Both targets are ticketing
    // siblings registered in discovery.
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate(
            @Value("${http.client.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${http.client.read-timeout-ms:5000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new CorrelationIdPropagatingInterceptor());
        return template;
    }
}
