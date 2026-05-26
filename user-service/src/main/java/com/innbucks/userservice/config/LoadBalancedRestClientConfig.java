package com.innbucks.userservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that resolves {@code http://<service-name>} URLs
 * through the Eureka registry via Spring Cloud LoadBalancer. The loyalty-service
 * client clones this builder so its calls are discovery-routed.
 *
 * <p>The external Oradian middleware client (see {@code OradianClientConfig})
 * deliberately does NOT use this builder — Oradian is not registered in our
 * Eureka, so it keeps a plain {@code RestClient.builder()} with an explicit URL.
 */
@Configuration
public class LoadBalancedRestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
