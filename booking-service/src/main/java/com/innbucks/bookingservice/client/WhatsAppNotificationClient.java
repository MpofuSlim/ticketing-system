package com.innbucks.bookingservice.client;

import com.innbucks.bookingservice.config.WhatsAppProperties;
import com.innbucks.bookingservice.util.MsisdnMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Sends messages through the external WhatsApp notification gateway's
 * {@code POST /api/messages/custom-notification} endpoint (single free-text
 * message, max 1600 chars, authenticated with an {@code x-api-key} header).
 *
 * <p>Used by {@code BookingConfirmedNotificationListener} to deliver a
 * booking-confirm notification to the customer. WhatsApp is the primary
 * channel — it's cheap, supports read receipts, and renders longer copy than
 * SMS. Any rejection / connectivity failure is surfaced as a
 * {@link NotificationDeliveryException} so the listener can fall back to SMS.
 */
@Slf4j
@Component
public class WhatsAppNotificationClient {

    private static final String CUSTOM_NOTIFICATION_PATH = "/api/messages/custom-notification";
    private static final String EVENT_QR_CODE_PATH = "/api/messages/event-qr-code";
    private static final String API_KEY_HEADER = "x-api-key";
    static final int MAX_MESSAGE_LENGTH = 1600;

    private final RestClient restClient;
    private final WhatsAppProperties properties;

    public WhatsAppNotificationClient(@Qualifier("whatsAppRestClient") RestClient restClient,
                                      WhatsAppProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public void sendCustomNotification(String to, String notification) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Recipient phone number is blank");
        }
        if (notification == null || notification.isBlank()) {
            throw new NotificationDeliveryException("Notification message is blank");
        }
        if (notification.length() > MAX_MESSAGE_LENGTH) {
            throw new NotificationDeliveryException(
                    "Notification exceeds the gateway's " + MAX_MESSAGE_LENGTH + "-character limit");
        }
        try {
            restClient.post()
                    .uri(CUSTOM_NOTIFICATION_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "notification", notification))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WhatsApp notification sent to={}", MsisdnMasking.mask(to));
        } catch (RestClientResponseException ex) {
            log.warn("WhatsApp gateway rejected notification to={} status={} body={}",
                    MsisdnMasking.mask(to), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway rejected the message: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp gateway unreachable to={} message={}", MsisdnMasking.mask(to), ex.getMessage());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway is unreachable: " + ex.getMessage(), ex);
        }
    }

    /**
     * Sends a scannable e-ticket via the gateway's
     * {@code POST /api/messages/event-qr-code} endpoint (a pre-approved Twilio
     * Content Template: "Event confirmed! Here is your e-ticket entry for
     * {eventName}. Only present this ticket at the gate."). The QR is delivered
     * as a WhatsApp media image which the gateway fetches from
     * {@code BASE_URL + qrCodePath}.
     *
     * <p>{@code qrCodePath} MUST be a domain-relative path starting with
     * {@code /} (e.g. {@code /bookings/{id}/tickets/{tn}/qr}) — the gateway
     * prepends its own production {@code BASE_URL}, so passing an absolute URL
     * would double the domain. The target must be a publicly reachable PNG; our
     * hosted ticket-QR endpoint is public and only serves CONFIRMED bookings.
     *
     * <p>Same {@code x-api-key} auth and failure contract as
     * {@link #sendCustomNotification} — any rejection / connectivity failure is
     * raised as {@link NotificationDeliveryException} for the caller to handle
     * (the confirm listener treats each ticket image as independent best-effort).
     */
    public void sendEventQrCode(String to, String eventName, String qrCodePath) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Recipient phone number is blank");
        }
        if (eventName == null || eventName.isBlank()) {
            throw new NotificationDeliveryException("eventName is blank");
        }
        if (qrCodePath == null || qrCodePath.isBlank() || !qrCodePath.startsWith("/")) {
            // The gateway prepends BASE_URL, so the path must be domain-relative.
            throw new NotificationDeliveryException("qrCodePath must be a path starting with '/'");
        }
        try {
            restClient.post()
                    .uri(EVENT_QR_CODE_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "eventName", eventName, "qrCodePath", qrCodePath))
                    .retrieve()
                    .toBodilessEntity();
            log.info("WhatsApp e-ticket QR sent to={} qrCodePath={}", MsisdnMasking.mask(to), qrCodePath);
        } catch (RestClientResponseException ex) {
            log.warn("WhatsApp gateway rejected e-ticket QR to={} status={} body={}",
                    MsisdnMasking.mask(to), ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway rejected the e-ticket QR: HTTP " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp gateway unreachable for e-ticket QR to={} message={}", MsisdnMasking.mask(to), ex.getMessage());
            throw new NotificationDeliveryException(
                    "WhatsApp gateway is unreachable: " + ex.getMessage(), ex);
        }
    }
}
