package innbucks.paymentservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that resolves {@code http://<service-name>} URLs
 * through the Eureka registry via Spring Cloud LoadBalancer. The ticketing
 * inter-service clients (booking-, loyalty-service) clone this builder so their
 * calls are discovery-routed.
 *
 * <p>The external Oradian middleware client deliberately does NOT use this
 * builder — it keeps a plain {@code RestClient.builder()} with an explicit URL,
 * because Oradian is not registered in our Eureka.
 */
@Configuration
public class LoadBalancedRestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
