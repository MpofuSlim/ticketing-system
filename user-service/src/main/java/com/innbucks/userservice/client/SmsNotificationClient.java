package com.innbucks.userservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends SMS notifications through the {@code innbucks-core-gateway} adapter,
 * which routes them to InnBucks {@code messenger-interface} via Feign.
 *
 * <p>The adapter is a host-resident service (not in the ticketing Eureka), so
 * it is reached via an explicit URL backed by {@link
 * com.innbucks.userservice.config.InnbucksGatewayProperties}. Failures are
 * surfaced as {@link NotificationDeliveryException} so callers can apply the
 * same fallback or rollback semantics as for WhatsApp delivery.
 *
 * <p>Never logs the message body — it may contain an OTP or a password.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private static final String SMS_PATH = "/notifications/sms";

    private final RestClient restClient;

    public SmsNotificationClient(@Qualifier("innbucksGatewayRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Dispatch an SMS to {@code destination} (E.164, e.g. {@code +263771234567}).
     * The adapter submits synchronously to messenger-interface and returns 200
     * once the notification is accepted into its queue; a non-2xx (502/503) or a
     * connectivity failure becomes a {@link NotificationDeliveryException} so the
     * caller can fall back to another channel (WhatsApp).
     */
    public void sendSms(String destination, String message, String reference) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-SMS-" + UUID.randomUUID();
        Map<String, String> body = new HashMap<>();
        body.put("destination", destination);
        body.put("message", message);
        body.put("reference", ref);
        body.put("senderId", "INNBUCKS");
        try {
            restClient.post()
                    .uri(SMS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("SMS notification accepted by gateway destination={} ref={}", destination, ref);
        } catch (RestClientResponseException ex) {
            log.warn("InnBucks gateway rejected SMS destination={} ref={} status={} body={}",
                    destination, ref, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "InnBucks gateway rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("InnBucks gateway unreachable destination={} ref={} error={}",
                    destination, ref, ex.getMessage());
            throw new NotificationDeliveryException(
                    "InnBucks gateway unreachable: " + ex.getMessage(), ex);
        }
    }
}
