package com.innbucks.eventservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells booking-service to notify a cancelled/changed event's confirmed
 * attendees. The trigger lives here (event-service owns the event lifecycle)
 * but the attendee phone numbers live in booking-service, so event-service
 * hands off via the internal {@code POST /bookings/internal/events/{eventId}/
 * change-notification} endpoint (authenticated with the shared
 * {@code X-Internal-Token}, mirrors {@link OrganizerGateway}).
 *
 * <p>Best-effort: wrapped in a circuit breaker whose fallback logs and returns
 * so a booking-service outage never fails the organizer's update/cancel — the
 * event change has already committed. booking-service does the actual SMS/
 * WhatsApp fan-out asynchronously, so this call returns quickly (202).
 */
@Component
@Slf4j
public class BookingNotificationGateway {

    public static final String CHANGE_UPDATED = "UPDATED";
    public static final String CHANGE_CANCELLED = "CANCELLED";

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;
    private final String bookingServiceBaseUrl;
    private final String internalToken;

    public BookingNotificationGateway(
            RestTemplate restTemplate,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Value("${booking-service.base-url:http://booking-service}") String bookingServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("bookingEventChangeNotify");
        this.bookingServiceBaseUrl = bookingServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Fire the attendee-notification broadcast for an event change. {@code
     * changeType} is {@link #CHANGE_UPDATED} or {@link #CHANGE_CANCELLED};
     * {@code newStartDateTime} / {@code newVenue} are only populated (and only
     * for UPDATED) when that field actually changed — they go straight into the
     * customer-facing message, so a null means "don't mention it".
     */
    public void notifyEventChange(UUID eventId, String changeType, String eventTitle,
                                  String newStartDateTime, String newVenue) {
        if (eventId == null) {
            return;
        }
        circuitBreaker.run(
                () -> {
                    doPost(eventId, changeType, eventTitle, newStartDateTime, newVenue);
                    return null;
                },
                throwable -> {
                    // Best-effort: the event change is already committed. Log and move on.
                    log.warn("bookingEventChangeNotify breaker fallback eventId={} changeType={}",
                            eventId, changeType, throwable);
                    return null;
                });
    }

    private void doPost(UUID eventId, String changeType, String eventTitle,
                        String newStartDateTime, String newVenue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);

        // HashMap (not Map.of) — newStartDateTime / newVenue may be null.
        Map<String, Object> body = new HashMap<>();
        body.put("changeType", changeType);
        body.put("eventTitle", eventTitle);
        body.put("newStartDateTime", newStartDateTime);
        body.put("newVenue", newVenue);

        String url = bookingServiceBaseUrl + "/bookings/internal/events/" + eventId + "/change-notification";
        restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Void.class);
        log.info("Requested event-change attendee notification eventId={} changeType={}", eventId, changeType);
    }
}
