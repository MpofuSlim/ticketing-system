package com.innbucks.userservice.notification;

import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.event.ServiceRequestDecided;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells a requester what an admin decided about their service-bundle request.
 *
 * <p><b>The gap this closes.</b> Approval and rejection were both silent. A
 * merchant learned their marketplace request had been granted by opening the
 * app and noticing a new menu item; a rejected request looked identical to one
 * still waiting, so people re-submitted the same request rather than fixing
 * whatever the admin objected to.
 *
 * <p>Runs {@code AFTER_COMMIT} so a decision that rolled back never tells
 * anyone it happened, and {@code @Async} so the admin's response is not held
 * behind an outbound HTTP call. Delivery itself is
 * {@link UserNotificationDispatcher}'s email-first / WhatsApp-fallback chain,
 * which never throws — a notification failure must not look like a failed
 * decision, and the decision is already durable by the time we are called.
 */
@Component
@Slf4j
public class ServiceRequestDecisionListener {

    private final UserNotificationDispatcher dispatcher;
    private final NotificationService notifications;

    public ServiceRequestDecisionListener(UserNotificationDispatcher dispatcher,
                                          NotificationService notifications) {
        this.dispatcher = dispatcher;
        this.notifications = notifications;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onServiceRequestDecided(ServiceRequestDecided event) {
        String subject = subjectFor(event);
        String message = bodyFor(event);
        log.debug("Notifying requester of service-request decision requestId={} outcome={}",
                event.requestId(), event.outcome());
        // The in-app copy is what §1.5 actually asked for: the REQUESTER told,
        // not just the admin queue. Recorded even when the account has no email
        // or phone on file — the bell is the one channel that always exists.
        if (event.userUuid() != null) {
            boolean approved = event.outcome() == ServiceRequestDecided.Outcome.APPROVED;
            notifications.create(new NotificationService.NewNotification(
                    event.userUuid(),
                    approved ? NotificationType.SERVICE_REQUEST_APPROVED
                             : NotificationType.SERVICE_REQUEST_REJECTED,
                    subject,
                    message,
                    approved ? Notification.Severity.SUCCESS : Notification.Severity.WARNING,
                    null, null,
                    "SERVICE_REQUEST", String.valueOf(event.requestId()),
                    // The requester's own view of their requests, not the admin
                    // queue they cannot open.
                    "/account/service-requests?highlight=" + event.requestId()));
        }
        dispatcher.dispatch(event.email(), event.phoneNumber(), subject, message);
    }

    private static String subjectFor(ServiceRequestDecided event) {
        return event.outcome() == ServiceRequestDecided.Outcome.APPROVED
                ? "Your " + event.service() + " access has been approved"
                : "Your " + event.service() + " access request was not approved";
    }

    /**
     * Deliberately plain ASCII: the notification API rejects non-ASCII subjects
     * and GSM-unsafe characters cost extra SMS parts, the same constraint the
     * booking-service copy works under.
     */
    private static String bodyFor(ServiceRequestDecided event) {
        if (event.outcome() == ServiceRequestDecided.Outcome.APPROVED) {
            StringBuilder sb = new StringBuilder()
                    .append("Good news - your request for ").append(event.service())
                    .append(" access on InnBucks has been approved.\n\n")
                    // The claims ride in the JWT, so the grant is invisible until
                    // a fresh token is minted. Saying so here is what stops a
                    // "it says approved but I still cannot see it" support ticket.
                    .append("Please sign out and sign in again to pick up your new access.");
            if (hasText(event.decisionReason())) {
                sb.append("\n\nNote from the reviewer: ").append(event.decisionReason());
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder()
                .append("Your request for ").append(event.service())
                .append(" access on InnBucks was not approved.");
        if (hasText(event.decisionReason())) {
            sb.append("\n\nReason: ").append(event.decisionReason());
        }
        // A rejection decides one request, not the bundle forever (the pending
        // uniqueness index is scoped to PENDING rows), so say that plainly.
        sb.append("\n\nYou can submit a new request once the point above is addressed.");
        return sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
