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
    private final String ticketizeBaseUrl;

    public OrganizerNotificationGateway(
            RestTemplate restTemplate,
            @Value("${user-service.base-url:http://user-service}") String userServiceBaseUrl,
            @Value("${innbucks.internal-api-token:}") String internalToken,
            // Public site the customer-facing event pages live on (the ticketing
            // PRODUCT is "Ticketize", hosted under the InnBucks domain). Used to
            // build the shareable event link in the "now live" email.
            @Value("${ticketize.public-base-url:https://ticketize.innbucks.co.zw}") String ticketizeBaseUrl) {
        this.restTemplate = restTemplate;
        this.userServiceBaseUrl = userServiceBaseUrl;
        this.internalToken = internalToken;
        this.ticketizeBaseUrl = stripTrailingSlash(ticketizeBaseUrl);
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
        String message = "Hello,\n\n"
                + "Good news — your event \"" + safeTitle(eventTitle) + "\" has been approved. "
                + "You can now publish it on Ticketize whenever you're ready, and it will go live "
                + "for customers to discover and buy tickets.\n\n"
                + "You can manage your event any time from your organizer dashboard.";
        notify(organizerUuid, subject, message);
    }

    /**
     * Notify {@code organizerUuid} that their event went live (activated /
     * published). No-op on a null uuid; never throws.
     */
    public void notifyEventActivated(UUID organizerUuid, UUID eventId, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event is now live on Ticketize";
        StringBuilder message = new StringBuilder("Hello,\n\n")
                .append("Great news — your event \"").append(safeTitle(eventTitle))
                .append("\" is now live on Ticketize and visible to customers. Ticket sales are open.\n\n");
        // Shareable public event link (only when we have the id) — clients
        // auto-linkify the bare https URL.
        if (eventId != null) {
            message.append("View your event: ").append(ticketizeBaseUrl)
                    .append("/events/").append(eventId).append("\n\n");
        }
        message.append("You can track bookings and revenue any time from your organizer dashboard. ")
                .append("We hope it's a great event!");
        notify(organizerUuid, subject, message.toString());
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
        String message = "Hello,\n\n"
                + "Your event \"" + safeTitle(eventTitle) + "\" has been deactivated and is no longer "
                + "visible to customers on Ticketize.\n\n"
                + "You can re-activate it any time from your organizer dashboard when you're ready.";
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
        String message = "Hello,\n\n"
                + "Your event \"" + safeTitle(eventTitle) + "\" has been reviewed and, on this occasion, "
                + "was not approved, so it is not visible to customers on Ticketize.\n\n"
                + "Please review your event details and resubmit, or contact our support team if you'd "
                + "like help understanding what to change.";
        notify(organizerUuid, subject, message);
    }

    private static String safeTitle(String eventTitle) {
        return (eventTitle == null || eventTitle.isBlank()) ? "Your event" : eventTitle.trim();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://ticketize.innbucks.co.zw";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
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
