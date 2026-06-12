package com.innbucks.bookingservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends email through the {@code innbucks-core-gateway} adapter's
 * {@code POST /notifications/email} endpoint → InnBucks {@code
 * messenger-interface} (core platform v2). Mirror of user-service's client of
 * the same name — uses the {@code innbucksGatewayRestClient} bean
 * {@link SmsNotificationClient} already uses.
 *
 * <p>Used by {@link com.innbucks.bookingservice.messaging.BookingConfirmedNotificationListener}
 * to deliver the confirmed-ticket email (HTML body with the scan QR served
 * from the hosted ticket endpoint). The body is pre-rendered by the caller —
 * messenger-interface runs no template engine on this path. Failures surface
 * as {@link NotificationDeliveryException} so the listener applies the same
 * best-effort semantics as the SMS/WhatsApp channels. Never logs subject/body
 * — a ticket email carries scannable bearer content.
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String EMAIL_PATH = "/notifications/email";

    private final RestClient restClient;

    public EmailNotificationClient(@Qualifier("innbucksGatewayRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** Single-recipient HTML email — the confirmed-ticket case. */
    public void sendHtmlEmail(String to, String subject, String htmlBody, String reference) {
        String[] cleanTo = stripBlank(to == null ? null : new String[]{to});
        if (cleanTo.length == 0) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (htmlBody == null || htmlBody.isBlank()) {
            throw new NotificationDeliveryException("Email body is blank");
        }
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-EMAIL-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", cleanTo);
        payload.put("subject", subject);
        payload.put("body", htmlBody);
        payload.put("isHtml", true);
        payload.put("reference", ref);

        try {
            restClient.post()
                    .uri(EMAIL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Email notification accepted by gateway recipients={} ref={}", cleanTo.length, ref);
        } catch (RestClientResponseException ex) {
            log.warn("InnBucks gateway rejected email recipients={} ref={} status={} body={}",
                    cleanTo.length, ref, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "InnBucks gateway rejected email: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("InnBucks gateway unreachable for email ref={} error={}", ref, ex.getMessage());
            throw new NotificationDeliveryException(
                    "InnBucks gateway unreachable: " + ex.getMessage(), ex);
        }
    }

    private static String[] stripBlank(String[] addresses) {
        if (addresses == null) {
            return new String[0];
        }
        return Arrays.stream(addresses)
                .filter(a -> a != null && !a.isBlank())
                .toArray(String[]::new);
    }
}
