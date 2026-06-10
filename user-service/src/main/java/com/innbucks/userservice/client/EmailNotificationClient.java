package com.innbucks.userservice.client;

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
 * Sends email notifications through the {@code innbucks-core-gateway} adapter's
 * {@code POST /notifications/email} endpoint, which forwards them to InnBucks
 * {@code messenger-interface} on the canonical v1 notification path.
 *
 * <p>The adapter is a host-resident service (not in the ticketing Eureka), so
 * it is reached via an explicit URL backed by {@link
 * com.innbucks.userservice.config.InnbucksGatewayProperties} — the same {@code
 * innbucksGatewayRestClient} bean {@link SmsNotificationClient} uses. A non-2xx
 * (502 when messenger-interface rejected, 503 when it was unreachable) or a
 * connectivity failure becomes a {@link NotificationDeliveryException} so
 * callers can apply the same best-effort / fallback semantics as the other
 * channels.
 *
 * <p>The body is pre-rendered by the caller — messenger-interface runs no
 * template engine on this path; it forwards the rendered subject/body straight
 * to SMTP. {@code isHtml} flips the content type the provider sends.
 *
 * <p>Never logs the subject or body — a credential / onboarding email or a
 * ticket may carry sensitive content.
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String EMAIL_PATH = "/notifications/email";

    private final RestClient restClient;

    public EmailNotificationClient(@Qualifier("innbucksGatewayRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Dispatch a single-recipient HTML email — the common transactional case
     * (staff credentials, account approval, a confirmed-ticket email). Delegates
     * to {@link #sendEmail(String[], String[], String[], String, String, boolean, String)}
     * with {@code isHtml=true} and no cc/bcc.
     */
    public void sendEmail(String to, String subject, String htmlBody, String reference) {
        sendEmail(to == null ? null : new String[]{to}, null, null,
                subject, htmlBody, true, reference);
    }

    /**
     * Dispatch an email to one or more recipients. Blank/null entries in
     * {@code to}/{@code cc}/{@code bcc} are stripped; at least one non-blank
     * {@code to} recipient is required. A null/blank {@code reference} is
     * replaced with a {@code TKT-EMAIL-<uuid>} key so messenger-interface
     * always has an idempotency / status-lookup handle (matching the adapter's
     * own default).
     *
     * @throws NotificationDeliveryException on a blank recipient/subject/body,
     *         a non-2xx from the gateway, or a connectivity failure.
     */
    public void sendEmail(String[] to, String[] cc, String[] bcc,
                          String subject, String body, boolean isHtml, String reference) {
        String[] cleanTo = stripBlank(to);
        if (cleanTo.length == 0) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (body == null || body.isBlank()) {
            throw new NotificationDeliveryException("Email body is blank");
        }
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "TKT-EMAIL-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", cleanTo);
        String[] cleanCc = stripBlank(cc);
        if (cleanCc.length > 0) {
            payload.put("cc", cleanCc);
        }
        String[] cleanBcc = stripBlank(bcc);
        if (cleanBcc.length > 0) {
            payload.put("bcc", cleanBcc);
        }
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("isHtml", isHtml);
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
            log.warn("InnBucks gateway unreachable recipients={} ref={} error={}",
                    cleanTo.length, ref, ex.getMessage());
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
