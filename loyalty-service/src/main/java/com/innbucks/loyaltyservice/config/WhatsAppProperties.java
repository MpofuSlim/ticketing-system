package com.innbucks.loyaltyservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway. Third-party service
 * reached over the public internet (an ngrok tunnel in dev), NOT a ticketing
 * service in Eureka — so it's consumed via a plain RestClient with an explicit
 * {@code base-url}, never {@code lb://}.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
