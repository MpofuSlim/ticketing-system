package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway. It is a third-party
 * service (reached over the public internet / an ngrok tunnel in dev), NOT a
 * ticketing service registered in Eureka — so it is consumed via a plain
 * RestClient with an explicit {@code base-url}, never {@code lb://}.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    /** Gateway base URL, e.g. https://gateway.example.com (no trailing path). */
    private String baseUrl;
    /** Secret presented as the {@code x-api-key} header on every call. */
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
