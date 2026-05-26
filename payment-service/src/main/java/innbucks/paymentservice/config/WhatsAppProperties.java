package innbucks.paymentservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway. Third-party service
 * (an ngrok tunnel in dev), NOT a ticketing service in Eureka — consumed via a
 * plain RestClient with an explicit {@code base-url}.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
