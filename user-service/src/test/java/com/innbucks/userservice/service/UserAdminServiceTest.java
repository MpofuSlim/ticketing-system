package com.innbucks.userservice.service;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    @Test
    void firstActivation_approvesAssignsDefaultPasswordAndNotifies() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        // As created by /auth/register: inactive, unapproved, placeholder password.
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode("#Pass123")).thenReturn("encoded-default");

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, mock(AuditService.class)).setActive(1L, true);

        assertTrue(result.isActive());
        assertTrue(result.isApproved());
        assertTrue(result.isMustChangePassword());
        assertEquals("encoded-default", result.getPassword());
        verify(encoder).encode("#Pass123");
        // SMS is the primary channel: the first-time password is SMS'd to the
        // approved user, and the WhatsApp fallback is NOT touched on success.
        verify(sms).sendSms(eq("+263771234567"), contains("#Pass123"), anyString());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void firstActivation_fallsBackToWhatsAppWhenSmsFails() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode("#Pass123")).thenReturn("encoded-default");

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, mock(AuditService.class)).setActive(1L, true);

        // Approval still succeeds; the password falls back to WhatsApp.
        assertTrue(result.isApproved());
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("#Pass123"));
    }

    @Test
    void reactivation_doesNotResetPasswordOrNotify() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        // Already approved, later deactivated; user has since chosen their own password.
        User user = User.builder().id(2L).email("c@d.com").password("user-chosen")
                .active(false).approved(true).mustChangePassword(false).build();
        when(userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, mock(AuditService.class)).setActive(2L, true);

        assertTrue(result.isActive());
        assertEquals("user-chosen", result.getPassword());
        assertFalse(result.isMustChangePassword());
        verify(encoder, never()).encode(any());
        // Re-activation is not a first approval — no password is re-sent.
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void noOp_whenAlreadyActiveAndApproved() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        User user = User.builder().id(3L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(3L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, encoder, whatsApp, sms, mock(AuditService.class)).setActive(3L, true);

        verify(userRepo, never()).save(any());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void deactivation_ofApprovedUser_leavesPasswordUntouched() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        User user = User.builder().id(4L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, mock(AuditService.class)).setActive(4L, false);

        assertFalse(result.isActive());
        assertEquals("pw", result.getPassword());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void throwsNotFound_whenUserMissing() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class),
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(AuditService.class))
                        .setActive(99L, true));
    }

    // ---- audit_events recording for SUPER_ADMIN user activation/deactivation ----

    @Test
    void firstActivation_recordsUSER_APPROVED_withAdminAsActor() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(7L).email("merchant@acme.co.zw").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(7L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode("#Pass123")).thenReturn("encoded-default");

        AuditContext ctx = new AuditContext("203.0.113.5", "curl/8.4.0");
        new UserAdminService(userRepo, encoder,
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), audit)
                .setActive(7L, true, "admin@innbucks.co.zw", ctx);

        verify(audit).recordSuccess(
                eq(AuditEventType.USER_APPROVED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("7"), eq(AuditService.TARGET_TYPE_USER),
                argThat(metadata ->
                        Boolean.TRUE.equals(metadata.get("active"))
                                && Boolean.TRUE.equals(metadata.get("mustChangePassword"))
                                && "merchant@acme.co.zw".equals(metadata.get("targetEmail"))),
                eq(ctx));
    }

    @Test
    void reactivation_recordsUSER_ACTIVATED_notUSER_APPROVED() {
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        // Already-approved user that admin previously deactivated. Re-activating
        // is NOT an approval, so the event must be USER_ACTIVATED.
        User user = User.builder().id(8L).email("staff@acme.co.zw")
                .password("user-chosen").active(false).approved(true).mustChangePassword(false).build();
        when(userRepo.findById(8L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), audit)
                .setActive(8L, true, "admin@innbucks.co.zw", AuditContext.none());

        verify(audit).recordSuccess(
                eq(AuditEventType.USER_ACTIVATED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("8"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(),
                eq(AuditContext.none()));
    }

    @Test
    void deactivation_recordsUSER_DEACTIVATED() {
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(9L).email("staff@acme.co.zw")
                .password("pw").active(true).approved(true).build();
        when(userRepo.findById(9L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), audit)
                .setActive(9L, false, "admin@innbucks.co.zw", AuditContext.none());

        verify(audit).recordSuccess(
                eq(AuditEventType.USER_DEACTIVATED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("9"), eq(AuditService.TARGET_TYPE_USER),
                argThat(metadata -> Boolean.FALSE.equals(metadata.get("active"))),
                eq(AuditContext.none()));
    }

    @Test
    void noOpIdempotentRetry_recordsNoAudit() {
        // setActive(true) on an already-active+approved user is a no-op — the
        // observable state didn't change, so no audit row should land. Without
        // this guard, retries of the same admin button would flood audit_events
        // with duplicate USER_ACTIVATED rows for every retry of an already-active
        // user — making the audit table noisy and harder to investigate.
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(10L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(10L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), audit)
                .setActive(10L, true, "admin@innbucks.co.zw", AuditContext.none());

        verifyNoInteractions(audit);
    }

    @Test
    void noArgOverload_recordsSystemActor_forBackwardCompat() {
        // The setActive(id, active) overload used to be the only form. Tests /
        // background jobs that still call it get actor_type=SYSTEM so the
        // audit row still lands (vs. a null actor that would fail any
        // downstream "who did this" investigation).
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(11L).active(false).approved(true).password("pw").build();
        when(userRepo.findById(11L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), audit)
                .setActive(11L, true); // legacy overload — no admin email, no context

        verify(audit).recordSuccess(
                eq(AuditEventType.USER_ACTIVATED),
                eq("system"), eq(AuditService.ACTOR_TYPE_SYSTEM),
                eq("11"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(),
                eq(AuditContext.none()));
    }
}
