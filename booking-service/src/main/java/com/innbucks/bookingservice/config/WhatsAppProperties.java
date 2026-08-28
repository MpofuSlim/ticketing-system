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

    /**
     * The gateway path that sends the scannable e-ticket QR. Configurable
     * ONLY because the gateway resolves {@code qrCodePath} against a BASE_URL
     * it holds internally, and that base is pinned to PRODUCTION — so a
     * staging booking id 404s when Twilio fetches the media, and the e-ticket
     * silently fails with Twilio error 63019.
     *
     * <p>The gateway team's answer is a second endpoint that resolves media
     * against the staging origin, identical in headers and body. Which one we
     * call is therefore a per-cell deployment fact, not a code fact — hence a
     * property, defaulted to the PRODUCTION path so an unset environment keeps
     * today's behaviour exactly.
     */
    private String eventQrCodePath = "/api/messages/event-qr-code";
}
