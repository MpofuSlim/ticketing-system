package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.event.AccountLockedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the "your account was locked" security alert when an account is locked
 * after repeated failed sign-ins. Both channels (email + SMS) fire when a
 * contact is on file — a lockout is security-sensitive, so we reach the user
 * everywhere we can rather than treating one as a fallback. Best-effort: a
 * delivery failure never affects the (already-persisted) lock.
 *
 * <p>{@code fallbackExecution = true} so the listener runs whether or not the
 * triggering login ran inside a transaction. Brand follows the audience —
 * InnBucks for customers, SwiftInn for system users.
 */
@Component
@Slf4j
public class AccountSecurityNotificationListener {

    private final EmailNotificationClient email;
    private final SmsNotificationClient sms;

    public AccountSecurityNotificationListener(EmailNotificationClient email, SmsNotificationClient sms) {
        this.email = email;
        this.sms = sms;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountLocked(AccountLockedEvent event) {
        String brand = event.customer() ? "InnBucks" : "SwiftInn";
        String ref = "ACCOUNT-LOCKED-" + event.userId();
        String subject = brand + " account security alert";
        String message = buildMessage(brand, event);

        if (event.email() != null && !event.email().isBlank()) {
            try {
                email.sendEmail(event.email(), subject, message, ref);
                log.info("Account-locked email sent userId={}", event.userId());
            } catch (RuntimeException ex) {
                log.warn("Account-locked email failed userId={} (account still locked): {}",
                        event.userId(), ex.getMessage());
            }
        }
        if (event.phoneNumber() != null && !event.phoneNumber().isBlank()) {
            try {
                sms.sendSms(event.phoneNumber(), message, ref);
                log.info("Account-locked SMS sent userId={}", event.userId());
            } catch (RuntimeException ex) {
                log.warn("Account-locked SMS failed userId={} (account still locked): {}",
                        event.userId(), ex.getMessage());
            }
        }
    }

    private String buildMessage(String brand, AccountLockedEvent event) {
        String name = (event.firstName() != null && !event.firstName().isBlank()) ? event.firstName() : "there";
        String until = event.lockedUntil() == null ? "shortly" : event.lockedUntil().toString();
        return "Hi " + name + ",\n\n"
                + "Your " + brand + " account was locked after several failed sign-in attempts. "
                + "If this was you, you can try again after " + until + " (UTC). "
                + "If it wasn't you, please reset your password as soon as the lock lifts.\n\n"
                + "— The " + brand + " Team";
    }
}
