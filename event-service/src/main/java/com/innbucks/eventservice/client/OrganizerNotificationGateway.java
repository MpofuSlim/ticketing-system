package com.innbucks.eventservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Tells user-service to notify an organizer over their own channels (email →
 * WhatsApp), via the service-to-service {@code POST /users/internal/{uuid}/notify}
 * endpoint authenticated by the shared {@code X-Internal-Token} header (never a
 * user JWT, mirrors {@link OrganizerGateway}). user-service owns the notification
 * credentials + the user's contact, so event-service delegates rather than
 * duplicating a notification stack.
 *
 * <p>Used on event approval to tell the organizer their event is live. Strictly
 * best-effort: user-service returns 202 immediately (delivery is async there),
 * and any failure here (user-service down, timeout, bad token) is logged and
 * swallowed so it never fails the approval it accompanies.
 */
@Component
@Slf4j
public class OrganizerNotificationGateway {

    private final RestTemplate restTemplate;
    private final String userServiceBaseUrl;
    private final String internalToken;

    public OrganizerNotificationGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Notify {@code organizerUuid} that their event was approved (un-rejected).
     * No-op on a null uuid; never throws.
     */
    public void notifyEventApproved(UUID organizerUuid, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event has been approved";
        String message = "Your event \"" + safeTitle(eventTitle)
                + "\" has been approved and is now ready to publish on InnBucks.";
        notify(organizerUuid, subject, message);
    }

    /**
     * Notify {@code organizerUuid} that their event went live (activated /
     * published). No-op on a null uuid; never throws.
     */
    public void notifyEventActivated(UUID organizerUuid, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event is now live";
        String message = "Your event \"" + safeTitle(eventTitle)
                + "\" is now live on InnBucks and visible to customers.";
        notify(organizerUuid, subject, message);
    }

    /**
     * Notify {@code organizerUuid} that their event was deactivated
     * (unpublished). No-op on a null uuid; never throws.
     */
    public void notifyEventDeactivated(UUID organizerUuid, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event has been deactivated";
        String message = "Your event \"" + safeTitle(eventTitle)
                + "\" has been deactivated and is no longer visible to customers on InnBucks. "
                + "You can re-activate it from your dashboard when you're ready.";
        notify(organizerUuid, subject, message);
    }

    /**
     * Notify {@code organizerUuid} that an administrator rejected their event.
     * No-op on a null uuid; never throws.
     */
    public void notifyEventRejected(UUID organizerUuid, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event was not approved";
        String message = "Your event \"" + safeTitle(eventTitle)
                + "\" was reviewed by an administrator and has not been approved, so it is not "
                + "visible to customers on InnBucks. Please review the event details or contact "
                + "support for more information.";
        notify(organizerUuid, subject, message);
    }

    private static String safeTitle(String eventTitle) {
        return (eventTitle == null || eventTitle.isBlank()) ? "Your event" : eventTitle.trim();
    }

    private void notify(UUID userUuid, String subject, String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            HttpEntity<Map<String, String>> request =
                    new HttpEntity<>(Map.of("subject", subject, "message", message), headers);

            String url = userServiceBaseUrl + "/users/internal/" + userUuid + "/notify";
            restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
        } catch (RuntimeException e) {
            // Best-effort: a notification failure must never fail the action it
            // accompanies (approve/activate/deactivate/reject).
            log.warn("Organizer notification failed userUuid={} cause={}", userUuid, e.toString());
        }
    }
}
