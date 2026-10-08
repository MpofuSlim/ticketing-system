package com.innbucks.userservice.notification;

import com.innbucks.userservice.event.RegistrationRejected;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells an applicant their registration was not approved, and why.
 *
 * <p>Same shape as {@link ServiceRequestDecisionListener}: {@code AFTER_COMMIT},
 * so a rejection that rolled back never announces itself, and {@code @Async}, so
 * the administrator's response is not held behind an outbound call. Delivery is
 * {@link UserNotificationDispatcher}'s email-first / WhatsApp-fallback chain,
 * which never throws — the rejection is already durable when this runs.
 *
 * <p>Deliberately NO {@code fallbackExecution}: the event is only ever published
 * inside the rejection's transaction, and one published anywhere else must not
 * reach the applicant without a commit behind it. And no in-app notification:
 * the account it would be addressed to no longer exists.
 */
@Component
@Slf4j
public class RegistrationRejectionListener {

    /** Plain ASCII: the notification API refuses non-ASCII subjects. */
    static final String SUBJECT = "Your InnBucks registration was not approved";

    private final UserNotificationDispatcher dispatcher;

    public RegistrationRejectionListener(UserNotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationRejected(RegistrationRejected event) {
        log.debug("Notifying applicant of a rejected registration userId={}", event.userId());
        dispatcher.dispatch(event.email(), event.phoneNumber(), SUBJECT, bodyFor(event));
    }

    /**
     * The copy is plain ASCII apart from what the applicant and the administrator
     * typed. A personal registration has no business name, so the sentence drops
     * the "for ..." rather than naming the person as their own business.
     */
    static String bodyFor(RegistrationRejected event) {
        StringBuilder sb = new StringBuilder("Hi");
        if (hasText(event.firstName())) {
            sb.append(' ').append(event.firstName().strip());
        }
        sb.append(", your registration");
        if (hasText(event.businessName())) {
            sb.append(" for ").append(event.businessName().strip());
        }
        sb.append(" on InnBucks was not approved.\n\n")
                .append("Reason: ").append(event.reason()).append("\n\n")
                // A rejection removes the registration, so the same email, phone
                // and TIN are free again — say that plainly.
                .append("You can register again once you have addressed this.");
        return sb.toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
