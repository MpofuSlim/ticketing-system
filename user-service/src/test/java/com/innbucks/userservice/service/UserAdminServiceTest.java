package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    /** Shape of a generated temp password: two hyphen-separated 5-char groups
     *  (10 password chars + 1 hyphen for readability). Exact alphabet is
     *  pinned in TemporaryPasswordGeneratorTest. */
    private static final String TEMP_PW_SHAPE = "[A-Za-z0-9]{5}-[A-Za-z0-9]{5}";

    /** Capture the plaintext handed to encode() — it's the generated temp password. */
    private static String capturePassword(PasswordEncoder encoder) {
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(pw.capture());
        return pw.getValue();
    }

    @Test
    void firstActivation_approvesAssignsRandomPasswordAndEmailsCredentials() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        // As created by /auth/register: inactive, unapproved, placeholder password.
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-temp");

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(1L, true);

        assertTrue(result.isActive());
        assertTrue(result.isApproved());
        assertTrue(result.isMustChangePassword());
        assertEquals("encoded-temp", result.getPassword());

        // The generated password is per-user random, NOT the old public default.
        String generated = capturePassword(encoder);
        assertNotEquals("#Pass123", generated);
        assertTrue(generated.matches(TEMP_PW_SHAPE), "unexpected temp password shape: " + generated);

        // Email is the primary channel: the SAME generated password lands in the
        // email body, and the phone fallbacks are NOT touched.
        verify(email).sendEmail(eq("a@b.com"), anyString(), contains(generated), anyString());
        verifyNoInteractions(whatsApp, sms);
    }

    @Test
    void firstActivation_fallsBackToSmsWhenEmailFails() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        doThrow(new NotificationDeliveryException("email gateway down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-temp");

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(1L, true);

        assertTrue(result.isApproved());
        String generated = capturePassword(encoder);
        // Approval still succeeds; the SAME password falls back to SMS, WhatsApp untouched.
        verify(sms).sendSms(eq("+263771234567"), contains(generated), anyString());
        verifyNoInteractions(whatsApp);
    }

    @Test
    void firstActivation_fallsBackToWhatsAppWhenEmailAndSmsFail() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        doThrow(new NotificationDeliveryException("email gateway down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-temp");

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(1L, true);

        assertTrue(result.isApproved());
        String generated = capturePassword(encoder);
        // Both prior channels failed, WhatsApp is the last resort — same password.
        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains(generated));
    }

    @Test
    void firstActivation_usesSmsWhenNoEmailOnFile() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        // No email address — the email channel is skipped entirely (never guess).
        User user = User.builder().id(5L).phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(5L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-temp");

        new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(5L, true);

        String generated = capturePassword(encoder);
        verify(sms).sendSms(eq("+263771234567"), contains(generated), anyString());
        verifyNoInteractions(email, whatsApp);
    }

    @Test
    void reactivation_doesNotResetPasswordOrNotify() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        // Already approved, later deactivated; user has since chosen their own password.
        User user = User.builder().id(2L).email("c@d.com").password("user-chosen")
                .active(false).approved(true).mustChangePassword(false).build();
        when(userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(2L, true);

        assertTrue(result.isActive());
        assertEquals("user-chosen", result.getPassword());
        assertFalse(result.isMustChangePassword());
        verify(encoder, never()).encode(any());
        // Re-activation is not a first approval — no password is re-sent on any channel.
        verifyNoInteractions(whatsApp, sms, email);
    }

    @Test
    void noOp_whenAlreadyActiveAndApproved() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        User user = User.builder().id(3L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(3L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(3L, true);

        verify(userRepo, never()).save(any());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp, sms, email);
    }

    @Test
    void deactivation_ofApprovedUser_leavesPasswordUntouched() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        SmsNotificationClient sms = mock(SmsNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        User user = User.builder().id(4L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = new UserAdminService(userRepo, encoder, whatsApp, sms, email,
                mock(AuditService.class)).setActive(4L, false);

        assertFalse(result.isActive());
        assertEquals("pw", result.getPassword());
        verify(encoder, never()).encode(any());
        verifyNoInteractions(whatsApp, sms, email);
    }

    @Test
    void throwsNotFound_whenUserMissing() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class),
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(EmailNotificationClient.class), mock(AuditService.class))
                        .setActive(99L, true));
    }

    @Test
    void resetTemporaryPassword_rotatesFlagsChangeAndNotifies() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        AuditService audit = mock(AuditService.class);
        // Approved user who never got their onboarding notification.
        User user = User.builder().id(42L).email("alice@innbucks.co.zw").phoneNumber("+263771234567")
                .password("old-hash").active(true).approved(true).mustChangePassword(false)
                .roles(java.util.EnumSet.of(User.Role.EVENT_ORGANIZER)).build();
        when(userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("new-hash");

        User result = new UserAdminService(userRepo, encoder,
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class), email, audit)
                .resetTemporaryPassword(42L, "admin@innbucks.co.zw", AuditContext.none());

        assertEquals("new-hash", result.getPassword());
        assertTrue(result.isMustChangePassword());            // single-use again
        String generated = capturePassword(encoder);
        assertNotEquals("#Pass123", generated);
        assertTrue(generated.matches(TEMP_PW_SHAPE));
        // The fresh password is delivered (email primary).
        verify(email).sendEmail(eq("alice@innbucks.co.zw"), anyString(), contains(generated), anyString());
        verify(audit).recordSuccess(
                eq(AuditEventType.USER_TEMP_PASSWORD_RESET),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("42"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(), eq(AuditContext.none()));
    }

    @Test
    void resetTemporaryPassword_refusesSuperAdminTarget() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(true).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new UserAdminService(userRepo, encoder,
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(EmailNotificationClient.class), mock(AuditService.class))
                        .resetTemporaryPassword(1L, "admin@innbucks.co.zw", AuditContext.none()));

        assertTrue(ex.getReason() != null && ex.getReason().contains("SUPER_ADMIN"));
        // Nothing rotated, nothing saved.
        verify(encoder, never()).encode(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void resetTemporaryPassword_throwsNotFoundWhenMissing() {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class),
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(EmailNotificationClient.class), mock(AuditService.class))
                        .resetTemporaryPassword(99L, "admin@innbucks.co.zw", AuditContext.none()));
    }

    @Test
    void setActive_refusesSuperAdminTarget_403() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(true).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> new UserAdminService(userRepo, encoder,
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(EmailNotificationClient.class), audit)
                        .setActive(1L, /* active */ false, "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("SUPER_ADMIN"));
        // Nothing written, no audit row, no notification.
        verify(userRepo, never()).save(any());
        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    void setActive_refusesSuperAdminTarget_evenWhenReactivating() {
        // Same protection in the opposite direction — once SUPER_ADMIN is
        // deactivated (it cannot be, see above), no one could re-enable it.
        // Cover the active=true path too so a future regression that splits
        // the guard by direction is caught.
        UserRepository userRepo = mock(UserRepository.class);
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(false).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        assertThrows(ResponseStatusException.class,
                () -> new UserAdminService(userRepo, mock(PasswordEncoder.class),
                        mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                        mock(EmailNotificationClient.class), mock(AuditService.class))
                        .setActive(1L, /* active */ true, "admin@innbucks.co.zw", AuditContext.none()));
        verify(userRepo, never()).save(any());
    }

    @Test
    void firstActivation_recordsUSER_APPROVED_withAdminAsActor() {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(7L).email("merchant@acme.co.zw").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(userRepo.findById(7L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encoder.encode(anyString())).thenReturn("encoded-temp");

        AuditContext ctx = new AuditContext("203.0.113.5", "curl/8.4.0");
        new UserAdminService(userRepo, encoder,
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                mock(EmailNotificationClient.class), audit)
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
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                mock(EmailNotificationClient.class), audit)
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
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                mock(EmailNotificationClient.class), audit)
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
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(10L).active(true).approved(true).password("pw").build();
        when(userRepo.findById(10L)).thenReturn(Optional.of(user));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                mock(EmailNotificationClient.class), audit)
                .setActive(10L, true, "admin@innbucks.co.zw", AuditContext.none());

        verifyNoInteractions(audit);
    }

    @Test
    void noArgOverload_recordsSystemActor_forBackwardCompat() {
        UserRepository userRepo = mock(UserRepository.class);
        AuditService audit = mock(AuditService.class);
        User user = User.builder().id(11L).active(false).approved(true).password("pw").build();
        when(userRepo.findById(11L)).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        new UserAdminService(userRepo, mock(PasswordEncoder.class),
                mock(WhatsAppNotificationClient.class), mock(SmsNotificationClient.class),
                mock(EmailNotificationClient.class), audit)
                .setActive(11L, true); // legacy overload — no admin email, no context

        verify(audit).recordSuccess(
                eq(AuditEventType.USER_ACTIVATED),
                eq("system"), eq(AuditService.ACTOR_TYPE_SYSTEM),
                eq("11"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(),
                eq(AuditContext.none()));
    }
}
