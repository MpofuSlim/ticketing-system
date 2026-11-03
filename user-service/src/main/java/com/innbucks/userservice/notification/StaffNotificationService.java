package com.innbucks.userservice.notification;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tells platform staff about something they should look at (event-service
 * V17's review step): an in-app notification and an email to every active
 * holder of the roles that type is for.
 *
 * <p><b>The audience is decided HERE, by type, never by the caller.</b> The
 * internal token is shared by every service, so an endpoint that took a role
 * list would let any of them message any staff group about anything. A type
 * that is not in {@link #AUDIENCES} is refused. Adding one is a reviewed code
 * change, like adding a permission.
 *
 * <p><b>Who hears about events:</b> SUPER_ADMIN and PRODUCT_MANAGER, who can
 * approve or reject, and PRODUCT_OFFICER, who reads every organizer's events
 * (owner decision, 2026-11-03). Never support ({@code CALL_CENTER_*}): they
 * cannot act on an event, and it would spread organizers' event details across
 * the call centre. Built-in role names only: a custom role cannot be named in a
 * constant (the same limit as {@code ServiceRequestService.REVIEWER_ROLES}).
 *
 * <p>Best-effort per recipient: one account whose bell cannot be written does
 * not stop the others hearing.
 */
@Service
@Slf4j
public class StaffNotificationService {

    static final List<String> EVENT_REVIEWERS = List.of(
            User.Role.SUPER_ADMIN.name(),
            User.Role.PRODUCT_MANAGER.name(),
            User.Role.PRODUCT_OFFICER.name());

    static final Map<String, List<String>> AUDIENCES = Map.of(
            NotificationType.EVENT_SUBMITTED, EVENT_REVIEWERS,
            NotificationType.EVENT_PUBLISHED, EVENT_REVIEWERS);

    /**
     * A console route: one leading slash, then path and query characters only.
     * A link that is not one (an absolute URL, {@code //host}, a line break) is
     * dropped rather than mailed to staff under our name.
     */
    private static final Pattern CONSOLE_PATH = Pattern.compile("^/(?!/)[A-Za-z0-9/_\\-.?=&%]{0,300}$");

    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final UserNotificationDispatcher dispatcher;
    private final StaffAccountProperties staffProperties;

    public StaffNotificationService(UserRepository userRepository,
                                    NotificationService notifications,
                                    UserNotificationDispatcher dispatcher,
                                    StaffAccountProperties staffProperties) {
        this.userRepository = userRepository;
        this.notifications = notifications;
        this.dispatcher = dispatcher;
        this.staffProperties = staffProperties;
    }

    /** What the producer supplies. The audience is not part of it. */
    public record StaffNotice(String type, String subject, String message, String severity,
                              String actorId, String actorName,
                              String subjectKind, String subjectId, String deepLink) {}

    /** Whether {@code type} has an audience here. */
    public static boolean knownType(String type) {
        return type != null && AUDIENCES.containsKey(type);
    }

    /**
     * Records the notice for every active holder of the type's roles and emails
     * each. Returns how many accounts it reached.
     *
     * @throws IllegalArgumentException for a type with no audience
     */
    public int notifyStaff(StaffNotice notice) {
        List<String> roles = AUDIENCES.get(notice.type());
        if (roles == null) {
            throw new IllegalArgumentException("No staff audience for type " + notice.type());
        }
        String link = consolePath(notice.deepLink());
        String mailed = withLink(notice.message(), link);
        Notification.Severity severity = severity(notice.severity());
        int reached = 0;
        for (User staff : userRepository.findByActiveAndAnyRole(true, roles)) {
            UUID recipient = staff.getUserUuid();
            if (recipient == null) {
                continue;
            }
            try {
                // The bell first and synchronously: the email is @Async and
                // best-effort, so a mail outage costs the email, not the bell.
                notifications.create(new NotificationService.NewNotification(
                        recipient, notice.type(), notice.subject(), notice.message(), severity,
                        notice.actorId(), notice.actorName(), notice.subjectKind(), notice.subjectId(), link));
                dispatcher.dispatch(staff.getEmail(), staff.getPhoneNumber(), notice.subject(), mailed);
                reached++;
            } catch (RuntimeException ex) {
                log.warn("Staff notification not recorded type={} recipient={} cause={}",
                        notice.type(), recipient, ex.toString());
            }
        }
        log.info("Staff notified type={} subjectId={} recipients={}", notice.type(), notice.subjectId(), reached);
        return reached;
    }

    static String consolePath(String deepLink) {
        if (deepLink == null) {
            return null;
        }
        String trimmed = deepLink.trim();
        return CONSOLE_PATH.matcher(trimmed).matches() ? trimmed : null;
    }

    /** The email carries the link as a full console URL when the console's address is configured. */
    private String withLink(String message, String path) {
        String base = staffProperties.consoleBaseUrl();
        if (path == null || base == null || base.isBlank()) {
            return message;
        }
        return message + "\n\nOpen it in the Foundry portal: " + base + path;
    }

    private static Notification.Severity severity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Notification.Severity.INFO;
        }
        try {
            return Notification.Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Notification.Severity.INFO;
        }
    }
}
