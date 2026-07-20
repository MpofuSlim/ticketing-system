package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link UserNotificationDispatcher}: pins email-primary /
 * WhatsApp-fallback ordering, the best-effort no-throw contract, and the guard
 * rails. Pure JUnit + Mockito (no Spring context; @Async is a no-op on a direct
 * call).
 */
class UserNotificationDispatcherTest {

    private EmailNotificationClient email;
    private WhatsAppNotificationClient whatsApp;
    private UserNotificationDispatcher dispatcher;

    private static final String EMAIL = "alice@example.com";
    private static final String PHONE = "+263771234567";
    private static final String SUBJECT = "Your event has been approved";
    private static final String MESSAGE = "Your event \"Summer Concert\" has been approved.";

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        whatsApp = mock(WhatsAppNotificationClient.class);
        dispatcher = new UserNotificationDispatcher(email, whatsApp);
    }

    @Test
    @DisplayName("email succeeds → WhatsApp never called")
    void emailSuccess_noFallback() {
        dispatcher.dispatch(EMAIL, PHONE, SUBJECT, MESSAGE);

        verify(email).sendEmail(eq(EMAIL), eq(SUBJECT), eq(MESSAGE), eq(null));
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("email throws → WhatsApp fallback with the message")
    void emailThrows_whatsAppFallback() {
        doThrow(new RuntimeException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));

        dispatcher.dispatch(EMAIL, PHONE, SUBJECT, MESSAGE);

        verify(whatsApp).sendCustomNotification(eq(PHONE), eq(MESSAGE));
    }

    @Test
    @DisplayName("both channels throw → no exception escapes")
    void bothThrow_noException() {
        doThrow(new RuntimeException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        doThrow(new RuntimeException("wa down"))
                .when(whatsApp).sendCustomNotification(eq(PHONE), anyString());

        assertThatCode(() -> dispatcher.dispatch(EMAIL, PHONE, SUBJECT, MESSAGE))
                .doesNotThrowAnyException();

        verify(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        verify(whatsApp).sendCustomNotification(eq(PHONE), anyString());
    }

    @Test
    @DisplayName("no email on file → WhatsApp used directly")
    void noEmail_whatsAppUsed() {
        dispatcher.dispatch("  ", PHONE, SUBJECT, MESSAGE);

        verifyNoInteractions(email);
        verify(whatsApp).sendCustomNotification(eq(PHONE), eq(MESSAGE));
    }

    @Test
    @DisplayName("email-only, email fails → no phone channel to try")
    void emailOnly_emailFails_noPhone() {
        doThrow(new RuntimeException("email down"))
                .when(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));

        assertThatCode(() -> dispatcher.dispatch(EMAIL, "  ", SUBJECT, MESSAGE))
                .doesNotThrowAnyException();

        verify(email).sendEmail(eq(EMAIL), anyString(), anyString(), eq(null));
        verifyNoInteractions(whatsApp);
    }

    @Test
    @DisplayName("no email and no phone → nothing touched")
    void noChannels_noop() {
        dispatcher.dispatch(null, "  ", SUBJECT, MESSAGE);

        verifyNoInteractions(email);
        verifyNoInteractions(whatsApp);
    }
}
