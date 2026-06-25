package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.service.UserAdminService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers the temporary password produced by {@code UserAdminService.setActive}
 * / {@code resetTemporaryPassword} to the user, off the HTTP request thread.
 *
 * <p>Was a synchronous helper inside {@code UserAdminService}; that path issued
 * up to three sequential outbound HTTP calls (email → SMS → WhatsApp) inside
 * the same transactional method, so a slow / failing upstream meant 30–48s of
 * wall-clock added to the admin {@code PUT /admin/users/{id}/active} response,
 * blew past the FE's {@code AbortController}, and surfaced as a misleading
 * "Request timeout" while the DB write had already committed.
 *
 * <p>Now: {@code @TransactionalEventListener(AFTER_COMMIT)} runs after the
 * row + audit are durable, {@code @Async} hops to {@code notificationExecutor}
 * so the request thread is freed inside ~100ms. Outcome (the channel that
 * actually delivered, or {@code all_failed}) goes to a Micrometer counter so
 * a silent total-failure surfaces in Grafana / alerts instead of only in a
 * buried {@code WARN} log line.
 *
 * <p>On any successful channel we call back into
 * {@link UserAdminService#markCredentialDelivered(Long)} so a retried
 * activation knows whether to re-fire — the original incident showed a user
 * stuck "approved but never reachable" because the idempotent no-op never
 * re-tried delivery.
 */
@Component
@Slf4j
public class CredentialDeliveryListener {

    private static final String METRIC = "user.credential.delivery";

    private final EmailNotificationClient email;
    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final UserAdminService userAdminService;
    private final MeterRegistry meters;

    public CredentialDeliveryListener(EmailNotificationClient email,
                                      SmsNotificationClient sms,
                                      WhatsAppNotificationClient whatsApp,
                                      UserAdminService userAdminService,
                                      MeterRegistry meters) {
        this.email = email;
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.userAdminService = userAdminService;
        this.meters = meters;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCredentialDeliveryRequested(CredentialDeliveryRequested event) {
        String ref = refPrefix(event.reason()) + event.userId();
        String subject = subjectFor(event.reason());
        String intro = introFor(event.reason());
        String emailBody = buildCredentialText(event.firstName(), event.email(),
                event.tempPassword(), intro);
        String smsBody = buildSmsBody(event.reason(), event.tempPassword());

        String outcome = tryDeliver(event, ref, subject, emailBody, smsBody);

        Counter.builder(METRIC)
                .description("Credential delivery attempts by terminal outcome")
                .tag("outcome", outcome)
                .tag("reason", event.reason().name().toLowerCase())
                .register(meters)
                .increment();

        if (!"all_failed".equals(outcome)) {
            userAdminService.markCredentialDelivered(event.userId());
        }
    }

    /** Returns the metric outcome label for the first channel that succeeded,
     *  or {@code all_failed} if every channel rejected / errored. */
    private String tryDeliver(CredentialDeliveryRequested event, String ref,
                              String subject, String emailBody, String smsBody) {
        String userEmail = event.email();
        if (userEmail != null && !userEmail.isBlank()) {
            try {
                email.sendEmail(userEmail, subject, emailBody, ref);
                log.info("Credential email sent userId={} ref={}", event.userId(), ref);
                return "email_sent";
            } catch (RuntimeException ex) {
                log.warn("Credential email failed userId={} ref={}, trying SMS: {}",
                        event.userId(), ref, ex.getMessage());
            }
        }

        String phone = event.phoneNumber();
        if (phone == null || phone.isBlank()) {
            log.warn("Credential delivery skipped — user has no phone fallback userId={} ref={}",
                    event.userId(), ref);
            return "all_failed";
        }
        try {
            sms.sendSms(phone, smsBody, ref);
            log.info("Credential SMS sent userId={} ref={}", event.userId(), ref);
            return "sms_fallback";
        } catch (RuntimeException ex) {
            log.warn("Credential SMS failed userId={} ref={}, trying WhatsApp: {}",
                    event.userId(), ref, ex.getMessage());
        }
        try {
            whatsApp.sendCustomNotification(phone, smsBody);
            log.info("Credential WhatsApp notification sent userId={} ref={}", event.userId(), ref);
            return "whatsapp_fallback";
        } catch (RuntimeException ex) {
            log.warn("Credential delivery failed userId={} ref={} (account state unchanged): {}",
                    event.userId(), ref, ex.getMessage());
            return "all_failed";
        }
    }

    private static String refPrefix(CredentialDeliveryRequested.Reason reason) {
        return reason == CredentialDeliveryRequested.Reason.APPROVAL ? "APPROVAL-" : "PWRESET-";
    }

    private static String subjectFor(CredentialDeliveryRequested.Reason reason) {
        return reason == CredentialDeliveryRequested.Reason.APPROVAL
                ? "Your SwiftInn account has been approved"
                : "Your SwiftInn temporary password has been reset";
    }

    private static String introFor(CredentialDeliveryRequested.Reason reason) {
        return reason == CredentialDeliveryRequested.Reason.APPROVAL
                ? "Good news — your SwiftInn account has been approved and is now active."
                : "Your SwiftInn temporary password has been reset by an administrator.";
    }

    private static String buildSmsBody(CredentialDeliveryRequested.Reason reason, String tempPassword) {
        if (reason == CredentialDeliveryRequested.Reason.APPROVAL) {
            return "Your SwiftInn account has been approved. Your temporary password is "
                    + tempPassword + ". Please log in and change it immediately.";
        }
        return "Your SwiftInn temporary password has been reset to "
                + tempPassword + ". Please log in and change it immediately.";
    }

    /** Identical to the prior {@code UserAdminService.buildCredentialText} body
     *  so existing recipients see no change in copy. */
    private static String buildCredentialText(String firstName, String email,
                                              String tempPassword, String intro) {
        String name = (firstName != null && !firstName.isBlank()) ? firstName : "there";
        return "Hi " + name + ",\n\n"
                + intro + "\n\n"
                + "Use these credentials to sign in:\n"
                + "Username: " + email + "\n"
                + "Temporary password: " + tempPassword + "\n\n"
                + "For your security, please log in and change your password immediately.\n\n"
                + "— The SwiftInn Team";
    }
}
