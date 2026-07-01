package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.event.AccountLockedEvent;
import com.innbucks.userservice.event.AccountSecurityAlertEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AccountSecurityNotificationListenerTest {

    private EmailNotificationClient email;
    private SmsNotificationClient sms;
    private AccountSecurityNotificationListener listener;

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        listener = new AccountSecurityNotificationListener(email, sms);
    }

    private static AccountLockedEvent event(boolean customer, String email, String phone) {
        return new AccountLockedEvent(1L, "Tariro", email, phone, customer,
                Instant.parse("2026-06-24T09:30:00Z"));
    }

    @Test
    void customerLock_usesInnBucksBrand_onBothChannels() {
        listener.onAccountLocked(event(true, "a@b.com", "+263771234567"));

        verify(email).sendEmail(eq("a@b.com"), contains("InnBucks"), contains("InnBucks"),
                startsWith("ACCOUNT-LOCKED-"));
        verify(sms).sendSms(eq("+263771234567"), contains("InnBucks"), startsWith("ACCOUNT-LOCKED-"));
    }

    @Test
    void systemUserLock_usesSwiftInnBrand() {
        listener.onAccountLocked(event(false, "ops@b.com", null));

        verify(email).sendEmail(eq("ops@b.com"), contains("SwiftInn"), contains("SwiftInn"), anyString());
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void noEmail_smsOnly() {
        listener.onAccountLocked(event(true, null, "+263771234567"));

        verify(sms).sendSms(eq("+263771234567"), anyString(), anyString());
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emailFailure_smsStillSent() {
        doThrow(new NotificationDeliveryException("gw down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> listener.onAccountLocked(event(true, "a@b.com", "+263771234567")))
                .doesNotThrowAnyException();
        verify(sms).sendSms(eq("+263771234567"), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // onSecurityAlert (password changed/reset, MFA on/off)
    // ------------------------------------------------------------------

    private static AccountSecurityAlertEvent alert(boolean customer, String email, String phone,
                                                   AccountSecurityAlertEvent.Type type) {
        return new AccountSecurityAlertEvent(7L, "Tariro", email, phone, customer, type);
    }

    @Test
    void passwordChanged_customer_bothChannels_innBucksBrand() {
        listener.onSecurityAlert(alert(true, "a@b.com", "+263771234567",
                AccountSecurityAlertEvent.Type.PASSWORD_CHANGED));

        verify(email).sendEmail(eq("a@b.com"), contains("InnBucks"), contains("password"),
                eq("SECURITY-PASSWORD_CHANGED-7"));
        verify(sms).sendSms(eq("+263771234567"), contains("password"), eq("SECURITY-PASSWORD_CHANGED-7"));
    }

    @Test
    void passwordReset_systemUser_usesSwiftInnBrand() {
        listener.onSecurityAlert(alert(false, "ops@b.com", null,
                AccountSecurityAlertEvent.Type.PASSWORD_RESET));

        verify(email).sendEmail(eq("ops@b.com"), contains("SwiftInn"), contains("SwiftInn"),
                eq("SECURITY-PASSWORD_RESET-7"));
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
    }

    @Test
    void mfaEnabled_message_saysTurnedOn_andNoActionNeeded() {
        listener.onSecurityAlert(alert(true, "a@b.com", "+263771234567",
                AccountSecurityAlertEvent.Type.MFA_ENABLED));

        verify(email).sendEmail(eq("a@b.com"), anyString(), contains("turned ON"),
                eq("SECURITY-MFA_ENABLED-7"));
        verify(sms).sendSms(eq("+263771234567"), contains("no action is needed"),
                eq("SECURITY-MFA_ENABLED-7"));
    }

    @Test
    void mfaDisabled_message_saysTurnedOff_andWarns() {
        listener.onSecurityAlert(alert(true, "a@b.com", "+263771234567",
                AccountSecurityAlertEvent.Type.MFA_DISABLED));

        verify(sms).sendSms(eq("+263771234567"), contains("turned OFF"), eq("SECURITY-MFA_DISABLED-7"));
        verify(email).sendEmail(eq("a@b.com"), anyString(), contains("reset your password"),
                eq("SECURITY-MFA_DISABLED-7"));
    }

    @Test
    void securityAlert_emailFailure_smsStillSent() {
        doThrow(new NotificationDeliveryException("gw down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> listener.onSecurityAlert(alert(true, "a@b.com", "+263771234567",
                AccountSecurityAlertEvent.Type.PASSWORD_CHANGED)))
                .doesNotThrowAnyException();
        verify(sms).sendSms(eq("+263771234567"), anyString(), anyString());
    }
}
