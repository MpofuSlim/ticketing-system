package innbucks.paymentservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the standalone {@code innbucks-core-gateway} adapter (SMS via
 * InnBucks messenger-interface). External to the ticketing fleet — not in the
 * Eureka registry, so a plain base URL, never {@code lb://}. Same env-var
 * convention ({@code INNBUCKS_GATEWAY_URL}) as user-service and
 * booking-service so one .env value configures every caller.
 */
@Data
@ConfigurationProperties(prefix = "innbucks-gateway")
public class InnbucksGatewayProperties {
    private String baseUrl;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
