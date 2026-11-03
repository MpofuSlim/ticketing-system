package com.innbucks.eventservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tells the event-review staff about an event (V17), through user-service's
 * {@code POST /users/internal/staff-notifications} with the shared
 * {@code X-Internal-Token}. user-service decides who hears (SUPER_ADMIN,
 * PRODUCT_MANAGER, PRODUCT_OFFICER) from the type; this side only says what
 * happened. Each gets an in-app notification and an email.
 *
 * <p>Best-effort, like {@link OrganizerNotificationGateway}: a failure is logged
 * and never fails the publish or submit it accompanies. Called after the
 * change has committed, so a rolled-back publish tells nobody.
 */
@Component
@Slf4j
public class StaffNotificationGateway {

    /** The organizer submitted the event for review (review step on). */
    public static final String EVENT_SUBMITTED = "EVENT_SUBMITTED";
    /** The event went live without a review (review step off). */
    public static final String EVENT_PUBLISHED = "EVENT_PUBLISHED";

    private final RestTemplate restTemplate;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public StaffNotificationGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    public void notifyReviewStaff(String type, UUID eventId, String subject, String message,
                                  UUID organizerUuid, String organizerName) {
        if (eventId == null || type == null) {
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            Map<String, String> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("subject", subject);
            body.put("message", message);
            body.put("severity", EVENT_SUBMITTED.equals(type) ? "WARNING" : "INFO");
            if (organizerUuid != null) {
                body.put("actorId", organizerUuid.toString());
            }
            if (organizerName != null && !organizerName.isBlank()) {
                body.put("actorName", organizerName);
            }
            body.put("subjectKind", "EVENT");
            body.put("subjectId", eventId.toString());
            body.put("deepLink", "/ticketing/events/" + eventId);

            restTemplate.exchange(userServiceBaseUrl + "/users/internal/staff-notifications",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
        } catch (RuntimeException e) {
            log.warn("Staff notification failed type={} eventId={} cause={}", type, eventId, e.toString());
        }
    }
}
