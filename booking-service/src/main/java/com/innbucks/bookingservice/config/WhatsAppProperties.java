package com.innbucks.bookingservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway used to deliver
 * booking-confirm notifications. Third-party service (not in Eureka), so
 * consumed via a plain RestClient with an explicit {@code base-url}. Same
 * env-var convention as user-service / payment-service so all three services
 * read the same {@code WHATSAPP_*} values from the deployment env.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;
}
