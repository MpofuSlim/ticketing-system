package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.service.UserAdminService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers the fallback chain (email -> SMS -> WhatsApp), the Micrometer outcome
 * metric, and the success callback to UserAdminService.markCredentialDelivered.
 *
 * <p>The @Async / @TransactionalEventListener wiring is Spring infrastructure
 * and isn't asserted here — those run as real listeners under the
 * @SpringBootTest classes (the original incident reproduced because the path
 * blocked the HTTP thread, so the integration-level coverage is more
 * meaningful than mocking out the executor).
 */
class CredentialDeliveryListenerTest {

    private EmailNotificationClient email;
    private SmsNotificationClient sms;
    private WhatsAppNotificationClient whatsApp;
    private UserAdminService userAdmin;
    private MeterRegistry meters;
    private CredentialDeliveryListener listener;

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        userAdmin = mock(UserAdminService.class);
        meters = new SimpleMeterRegistry();
        listener = new CredentialDeliveryListener(email, sms, whatsApp, userAdmin, meters);
    }

    private static CredentialDeliveryRequested event() {
        return event("a@b.com", "+263771234567", CredentialDeliveryRequested.Reason.APPROVAL);
    }

    private static CredentialDeliveryRequested event(String email, String phone,
                                                     CredentialDeliveryRequested.Reason reason) {
        return new CredentialDeliveryRequested(
                42L, "Alice", email, phone, "TEMP-abc-12345", reason);
    }

    private double counter(String outcome, String reason) {
        var meter = meters.find("user.credential.delivery")
                .tag("outcome", outcome).tag("reason", reason).counter();
        return meter == null ? 0.0 : meter.count();
    }

    // -- happy path: email delivers ------------------------------------------

    @Test
    void email_success_emitsEmailSent_andMarksDelivered() {
        listener.onCredentialDeliveryRequested(event());

        verify(email).sendEmail(eq("a@b.com"), contains("approved"),
                contains("TEMP-abc-12345"), eq("APPROVAL-42"));
        verifyNoInteractions(sms, whatsApp);
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("email_sent", "approval")).isEqualTo(1.0);
    }

    @Test
    void reset_event_usesPwresetRefAndResetCopy() {
        listener.onCredentialDeliveryRequested(
                event("a@b.com", "+263771234567", CredentialDeliveryRequested.Reason.RESET));

        verify(email).sendEmail(eq("a@b.com"), contains("reset"),
                contains("TEMP-abc-12345"), eq("PWRESET-42"));
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("email_sent", "reset")).isEqualTo(1.0);
    }

    @Test
    void onboarding_event_usesStaffOnboardRefAndWelcomeCopy() {
        listener.onCredentialDeliveryRequested(
                event("a@b.com", "+263771234567", CredentialDeliveryRequested.Reason.ONBOARDING));

        verify(email).sendEmail(eq("a@b.com"), contains("Welcome to SwiftInn"),
                contains("TEMP-abc-12345"), eq("STAFF-ONBOARD-42"));
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("email_sent", "onboarding")).isEqualTo(1.0);
    }

    // -- fallback chain -------------------------------------------------------

    @Test
    void emailFails_fallsBackToSms_andMarksDelivered() {
        doThrow(new NotificationDeliveryException("HTTP 403"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        listener.onCredentialDeliveryRequested(event());

        verify(sms).sendSms(eq("+263771234567"), contains("TEMP-abc-12345"), eq("APPROVAL-42"));
        verifyNoInteractions(whatsApp);
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("sms_fallback", "approval")).isEqualTo(1.0);
    }

    @Test
    void emailAndSmsFail_fallsBackToWhatsApp_andMarksDelivered() {
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        listener.onCredentialDeliveryRequested(event());

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("TEMP-abc-12345"));
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("whatsapp_fallback", "approval")).isEqualTo(1.0);
    }

    @Test
    void allChannelsFail_marksAllFailed_andDoesNotCallMarkDelivered() {
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("sms down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("whatsapp down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        listener.onCredentialDeliveryRequested(event());

        // Critical: do NOT mark delivered. The user is stuck "approved but
        // unreachable" — the all_failed metric is the signal for an alert,
        // and a retried activation will re-publish (UserAdminService logic).
        verify(userAdmin, never()).markCredentialDelivered(42L);
        assertThat(counter("all_failed", "approval")).isEqualTo(1.0);
    }

    // -- channel-skip cases --------------------------------------------------

    @Test
    void noEmail_phoneOnly_smsSucceeds() {
        listener.onCredentialDeliveryRequested(
                event(null, "+263771234567", CredentialDeliveryRequested.Reason.APPROVAL));

        verifyNoInteractions(email);
        verify(sms).sendSms(eq("+263771234567"), contains("TEMP-abc-12345"), eq("APPROVAL-42"));
        verify(userAdmin).markCredentialDelivered(42L);
        assertThat(counter("sms_fallback", "approval")).isEqualTo(1.0);
    }

    @Test
    void emailOnly_noPhone_emailFails_returnsAllFailed() {
        // No phone fallback available — email failure is terminal.
        doThrow(new NotificationDeliveryException("email down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        listener.onCredentialDeliveryRequested(
                event("a@b.com", null, CredentialDeliveryRequested.Reason.APPROVAL));

        verifyNoInteractions(sms, whatsApp);
        verify(userAdmin, never()).markCredentialDelivered(42L);
        assertThat(counter("all_failed", "approval")).isEqualTo(1.0);
    }

    @Test
    void noEmail_noPhone_returnsAllFailed_immediately() {
        listener.onCredentialDeliveryRequested(
                event(null, null, CredentialDeliveryRequested.Reason.APPROVAL));

        verifyNoInteractions(email, sms, whatsApp);
        verify(userAdmin, never()).markCredentialDelivered(42L);
        assertThat(counter("all_failed", "approval")).isEqualTo(1.0);
    }
}
