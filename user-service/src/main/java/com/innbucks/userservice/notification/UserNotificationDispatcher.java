package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Generic best-effort user notification used by the service-to-service
 * {@code POST /users/internal/{userUuid}/notify} endpoint: another backend
 * (e.g. event-service on event approval) supplies a subject + message and this
 * fans it out over the user's own channels.
 *
 * <p><b>Email-primary, WhatsApp-fallback.</b> System users (organizers,
 * merchant admins) have an email on file, so email is the first hop; WhatsApp
 * catches them if email fails or no email is on file. Runs on
 * {@code notificationExecutor} via {@link Async @Async} so the calling request
 * returns immediately, and it is strictly best-effort — every failure (no
 * channel, both channels down) is logged and swallowed; this method never
 * throws.
 */
@Slf4j
@Component
public class UserNotificationDispatcher {

    private final EmailNotificationClient email;
    private final WhatsAppNotificationClient whatsApp;

    public UserNotificationDispatcher(EmailNotificationClient email,
                                      WhatsAppNotificationClient whatsApp) {
        this.email = email;
        this.whatsApp = whatsApp;
    }

    @Async("notificationExecutor")
    public void dispatch(String emailAddress, String phone, String subject, String message) {
        boolean hasEmail = emailAddress != null && !emailAddress.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (!hasEmail && !hasPhone) {
            log.debug("User notification: no email or phone on file; skipping");
            return;
        }

        // Primary channel: email.
        if (hasEmail) {
            try {
                email.sendEmail(emailAddress, subject, message, null);
                return;
            } catch (RuntimeException e) {
                log.warn("User notification email failed, falling back to WhatsApp: {}", e.getMessage());
            }
        }

        if (!hasPhone) {
            log.warn("User notification: email failed and no phone on file; giving up");
            return;
        }

        // Fallback channel: WhatsApp.
        try {
            whatsApp.sendCustomNotification(phone, message);
        } catch (RuntimeException e) {
            log.warn("User notification failed on both email and WhatsApp: {}", e.getMessage());
        }
    }
}
