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
     * Review step on (V17): staff approved the event and it is live now.
     * {@code salesOpenNote} as in {@link #notifyEventActivated(UUID, UUID, String, String)}.
     */
    public void notifyEventApprovedAndLive(UUID organizerUuid, UUID eventId, String eventTitle,
                                           String salesOpenNote) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event has been approved and is live";
        StringBuilder message = new StringBuilder("Hello,\n\n")
                .append("Good news — your event \"").append(safeTitle(eventTitle))
                .append("\" has been approved and is now live on Ticketize. ")
                .append(salesOpenNote == null || salesOpenNote.isBlank() ? "Ticket sales are open." : salesOpenNote)
                .append("\n\n");
        if (eventId != null) {
            message.append("View your event: ").append(ticketizeBaseUrl)
                    .append("/events/").append(eventId).append("\n\n");
        }
        message.append("You can track bookings and revenue any time from your organizer dashboard.");
        notify(organizerUuid, subject, message.toString(), "EVENT_APPROVED", eventId);
    }

    /**
     * Notify {@code organizerUuid} that their event went live (activated /
     * published). No-op on a null uuid; never throws.
     */
    public void notifyEventActivated(UUID organizerUuid, UUID eventId, String eventTitle) {
        notifyEventActivated(organizerUuid, eventId, eventTitle, null);
    }

    /**
     * As {@link #notifyEventActivated(UUID, UUID, String)}, saying when sales
     * open: {@code salesOpenNote} (e.g. "Ticket sales open on Sat 14 Nov 2026
     * at 10:00.") replaces "Ticket sales are open." for an event whose sales
     * start later (V15).
     */
    public void notifyEventActivated(UUID organizerUuid, UUID eventId, String eventTitle, String salesOpenNote) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event is now live on Ticketize";
        StringBuilder message = new StringBuilder("Hello,\n\n")
                .append("Great news — your event \"").append(safeTitle(eventTitle))
                .append("\" is now live on Ticketize and visible to customers. ")
                .append(salesOpenNote == null || salesOpenNote.isBlank() ? "Ticket sales are open." : salesOpenNote)
                .append("\n\n");
        // Shareable public event link (only when we have the id) — clients
        // auto-linkify the bare https URL.
        if (eventId != null) {
            message.append("View your event: ").append(ticketizeBaseUrl)
                    .append("/events/").append(eventId).append("\n\n");
        }
        message.append("You can track bookings and revenue any time from your organizer dashboard. ")
                .append("We hope it's a great event!");
        notify(organizerUuid, subject, message.toString(), "EVENT_PUBLISHED", eventId);
    }

    /**
     * Review step on (V17): the organizer's publish sent the event to staff
     * rather than putting it live. Tells them it is waiting, so a click on
     * Publish that did not make the event visible is explained.
     */
    public void notifyEventSubmitted(UUID organizerUuid, UUID eventId, String eventTitle) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event is waiting for approval";
        String message = "Hello,\n\n"
                + "Thank you. Your event \"" + safeTitle(eventTitle) + "\" has been sent to our team for "
                + "review. It goes live on Ticketize as soon as it is approved, and we'll let you know.\n\n"
                + "You can keep editing it from your organizer dashboard while you wait.";
        notify(organizerUuid, subject, message, "EVENT_SUBMITTED", eventId);
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
        notifyEventRejected(organizerUuid, null, eventTitle, null);
    }

    /**
     * As {@link #notifyEventRejected(UUID, String)}, with what staff said
     * (V17). The reason is staff's own words, already stripped of markup.
     */
    public void notifyEventRejected(UUID organizerUuid, UUID eventId, String eventTitle, String reason) {
        if (organizerUuid == null) {
            return;
        }
        String subject = "Your event was not approved";
        StringBuilder message = new StringBuilder("Hello,\n\n")
                .append("Your event \"").append(safeTitle(eventTitle))
                .append("\" has been reviewed and, on this occasion, was not approved, so it is not visible ")
                .append("to customers on Ticketize.\n\n");
        if (reason != null && !reason.isBlank()) {
            message.append("Our team's note: ").append(reason.trim()).append("\n\n");
        }
        message.append("Please review your event details and resubmit, or contact our support team if you'd ")
                .append("like help understanding what to change.");
        notify(organizerUuid, subject, message.toString(), "EVENT_REJECTED", eventId);
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
        notify(userUuid, subject, message, null, null);
    }

    /**
     * {@code type} and {@code eventId}, when given, make the in-app copy
     * actionable: user-service stores them as the notification's type, subject
     * and console link (all optional on its side).
     */
    private void notify(UUID userUuid, String subject, String message, String type, UUID eventId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-Token", internalToken);

            Map<String, String> body = new java.util.LinkedHashMap<>();
            body.put("subject", subject);
            body.put("message", message);
            if (type != null) {
                body.put("type", type);
            }
            if (eventId != null) {
                body.put("subjectKind", "EVENT");
                body.put("subjectId", eventId.toString());
                body.put("deepLink", "/ticketing/events/" + eventId);
            }
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            String url = userServiceBaseUrl + "/users/internal/" + userUuid + "/notify";
            restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
        } catch (RuntimeException e) {
            // Best-effort: a notification failure must never fail the action it
            // accompanies (approve/activate/deactivate/reject).
            log.warn("Organizer notification failed userUuid={} cause={}", userUuid, e.toString());
        }
    }
}
