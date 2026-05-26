package com.innbucks.seatservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that resolves {@code http://<service-name>} URLs
 * through the Eureka registry via Spring Cloud LoadBalancer. The inter-service
 * clients (event-, booking-, user-service) clone this builder so every call is
 * discovery-routed instead of hitting a hardcoded host:port.
 */
@Configuration
public class LoadBalancedRestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
