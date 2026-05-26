package innbucks.paymentservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
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

    /**
     * The plain (non-load-balanced) builder, kept @Primary so anything that
     * autowires a RestClient.Builder by type gets this one — crucially the
     * Eureka client's own RestClient transport, which talks to the registry at
     * a fixed URL. If Eureka picked up the @LoadBalanced builder it would try to
     * resolve the registry host ("localhost") as a service id, which fails with
     * BeanCurrentlyInCreationException + "No instances available for localhost".
     * Prototype-scoped to mirror Spring Boot's auto-configured builder.
     */
    @Bean
    @Primary
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
