package com.innbucks.userservice.notification;

import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.ServiceSuspensionService.ServiceSuspensionChanged;
import com.innbucks.userservice.service.Services;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.List;

/**
 * Tells a business's OWNERs and ADMINs that SUPER_ADMIN suspended or reinstated
 * one of its services (V53): an in-app notification and an email (WhatsApp if
 * the email fails). AFTER_COMMIT, so a change that rolled back tells nobody,
 * and {@code @Async}, so the admin's answer does not wait on mail. No
 * {@code fallbackExecution}: the event is only ever published inside the
 * change's transaction.
 *
 * <p>Plain ASCII: the notification API refuses non-ASCII subjects.
 */
@Component
@Slf4j
public class ServiceSuspensionListener {

    private final OrganizationMemberRepository members;
    private final UserRepository users;
    private final NotificationService notifications;
    private final UserNotificationDispatcher dispatcher;

    public ServiceSuspensionListener(OrganizationMemberRepository members, UserRepository users,
                                     NotificationService notifications, UserNotificationDispatcher dispatcher) {
        this.members = members;
        this.users = users;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(ServiceSuspensionChanged event) {
        String subject = subject(event);
        String message = message(event);
        List<Long> runners = members.findByOrganizationIdAndRoleIn(event.organizationId(),
                EnumSet.of(OrganizationMember.Role.OWNER, OrganizationMember.Role.ADMIN)).stream()
                .map(OrganizationMember::getUserId).toList();
        int told = 0;
        for (User person : runners.isEmpty() ? List.<User>of() : users.findAllById(runners)) {
            if (!person.isActive()) {
                continue;
            }
            try {
                if (person.getUserUuid() != null) {
                    notifications.create(new NotificationService.NewNotification(person.getUserUuid(),
                            event.suspended() ? NotificationType.SERVICE_SUSPENDED : NotificationType.SERVICE_REINSTATED,
                            subject, message,
                            event.suspended() ? Notification.Severity.WARNING : Notification.Severity.SUCCESS,
                            null, null, "ORGANIZATION", event.organizationId().toString(), null));
                }
                dispatcher.dispatch(person.getEmail(), person.getPhoneNumber(), subject, message);
                told++;
            } catch (RuntimeException ex) {
                log.warn("Service suspension notice not recorded organizationId={} cause={}",
                        event.organizationId(), ex.toString());
            }
        }
        log.info("Service suspension notice organizationId={} product={} suspended={} told={}",
                event.organizationId(), event.product(), event.suspended(), told);
    }

    static String subject(ServiceSuspensionChanged event) {
        String service = Services.displayName(event.product());
        return event.suspended()
                ? "Your " + service + " service on InnBucks has been suspended"
                : "Your " + service + " service on InnBucks is back on";
    }

    static String message(ServiceSuspensionChanged event) {
        String service = Services.displayName(event.product());
        String business = event.organizationName() == null || event.organizationName().isBlank()
                ? "your business" : event.organizationName().trim();
        if (event.suspended()) {
            StringBuilder sb = new StringBuilder("Hello,\n\nThe ").append(service).append(" service for ")
                    .append(business).append(" on InnBucks has been suspended, so your team can't use it ")
                    .append("until it is reinstated. You have been signed out.");
            if (event.reason() != null && !event.reason().isBlank()) {
                sb.append("\n\nReason: ").append(event.reason());
            }
            return sb.append("\n\nIf you think this is a mistake, please contact InnBucks support.").toString();
        }
        return "Hello,\n\nThe " + service + " service for " + business + " on InnBucks has been reinstated. "
                + "Sign out and sign in again to use it.";
    }
}
