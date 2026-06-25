package com.innbucks.userservice.service;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserAdminService.
 *
 * <p>Credential delivery moved off this class into {@code CredentialDeliveryListener}
 * (see CredentialDeliveryListenerTest for the email->SMS->WhatsApp fallback chain).
 * The assertions here only cover what UserAdminService is still responsible for:
 * the state machine, audit emission, deactivation notification (still inline,
 * pending its own follow-up), and the {@link CredentialDeliveryRequested} event
 * it now publishes for the async listener.
 */
class UserAdminServiceTest {

    /** Shape of a generated temp password: two hyphen-separated 5-char groups
     *  (10 password chars + 1 hyphen for readability). Exact alphabet is
     *  pinned in TemporaryPasswordGeneratorTest. */
    private static final String TEMP_PW_SHAPE = "[A-Za-z0-9]{5}-[A-Za-z0-9]{5}";

    /** Build a UserAdminService with all collaborators mocked. Tests can grab
     *  the same mocks via the field accessors below. */
    private static class Fixture {
        final UserRepository userRepo = mock(UserRepository.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final SmsNotificationClient sms = mock(SmsNotificationClient.class);
        final EmailNotificationClient email = mock(EmailNotificationClient.class);
        final AuditService audit = mock(AuditService.class);
        final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        final UserAdminService service = new UserAdminService(
                userRepo, encoder, sms, email, audit, publisher);
    }

    /** Capture the plaintext handed to encode() — it's the generated temp password. */
    private static String capturePassword(PasswordEncoder encoder) {
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(pw.capture());
        return pw.getValue();
    }

    private static CredentialDeliveryRequested captureEvent(ApplicationEventPublisher publisher) {
        ArgumentCaptor<CredentialDeliveryRequested> cap =
                ArgumentCaptor.forClass(CredentialDeliveryRequested.class);
        verify(publisher).publishEvent(cap.capture());
        return cap.getValue();
    }

    // -- Approval / first activation -----------------------------------------

    @Test
    void firstActivation_approves_assignsRandomPassword_andPublishesApprovalEvent() {
        Fixture f = new Fixture();
        User user = User.builder().id(1L).email("a@b.com").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(f.userRepo.findById(1L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.encoder.encode(anyString())).thenReturn("encoded-temp");

        User result = f.service.setActive(1L, true);

        assertTrue(result.isActive());
        assertTrue(result.isApproved());
        assertTrue(result.isMustChangePassword());
        assertEquals("encoded-temp", result.getPassword());

        String generated = capturePassword(f.encoder);
        assertNotEquals("#Pass123", generated);
        assertTrue(generated.matches(TEMP_PW_SHAPE), "unexpected temp password shape: " + generated);

        // Hand-off to the async listener: the event carries the SAME plaintext
        // password that was encoded, plus enough identity for the listener to
        // pick channels without re-reading the user.
        CredentialDeliveryRequested ev = captureEvent(f.publisher);
        assertEquals(1L, ev.userId());
        assertEquals("a@b.com", ev.email());
        assertEquals("+263771234567", ev.phoneNumber());
        assertEquals(generated, ev.tempPassword());
        assertEquals(CredentialDeliveryRequested.Reason.APPROVAL, ev.reason());

        // No inline notification calls — that lived in this class before the
        // refactor; the listener owns delivery now.
        verifyNoInteractions(f.email, f.sms);
    }

    @Test
    void firstActivation_publishesEvenWhenOnlyPhoneOnFile() {
        // The listener decides which channels to try based on what's set on the
        // event; UserAdminService doesn't pre-filter. So even "no email" users
        // get an event — the listener will skip the email branch internally.
        Fixture f = new Fixture();
        User user = User.builder().id(5L).phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(f.userRepo.findById(5L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.encoder.encode(anyString())).thenReturn("encoded-temp");

        f.service.setActive(5L, true);

        CredentialDeliveryRequested ev = captureEvent(f.publisher);
        assertNull(ev.email());
        assertEquals("+263771234567", ev.phoneNumber());
    }

    // -- Idempotent / retry semantics ----------------------------------------

    @Test
    void noOp_whenAlreadyActiveAndCredentialDelivered() {
        // The classic happy-path retry: row already active, credentials were
        // delivered (timestamp set). The retry returns the existing user
        // unchanged, no save, no audit, no event.
        Fixture f = new Fixture();
        User user = User.builder().id(3L).active(true).approved(true)
                .mustChangePassword(true).credentialDeliveredAt(LocalDateTime.now())
                .password("pw").build();
        when(f.userRepo.findById(3L)).thenReturn(Optional.of(user));

        f.service.setActive(3L, true);

        verify(f.userRepo, never()).save(any());
        verify(f.encoder, never()).encode(any());
        verifyNoInteractions(f.publisher, f.audit);
    }

    @Test
    void retry_whenPreviousDeliveryFailed_rotatesPassword_andRepublishesEvent() {
        // The bug-fix path: an earlier activation committed the row but the
        // listener's fallback chain failed on every channel, so
        // credential_delivered_at is still NULL. A retried activation must
        // rotate the temp password and publish a fresh event so the user can
        // actually log in. Audit row is NOT re-emitted (the activation event
        // already happened on the first call); this is purely a re-delivery.
        Fixture f = new Fixture();
        User user = User.builder().id(4L).email("c@d.com").phoneNumber("+263771234567")
                .active(true).approved(true).mustChangePassword(true)
                .credentialDeliveredAt(null)
                .password("old-hash").build();
        when(f.userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.encoder.encode(anyString())).thenReturn("new-hash");

        User result = f.service.setActive(4L, true);

        assertEquals("new-hash", result.getPassword());
        assertTrue(result.isMustChangePassword());

        String generated = capturePassword(f.encoder);
        CredentialDeliveryRequested ev = captureEvent(f.publisher);
        assertEquals(generated, ev.tempPassword());
        assertEquals(CredentialDeliveryRequested.Reason.APPROVAL, ev.reason());
        verifyNoInteractions(f.audit);
    }

    @Test
    void reactivation_doesNotResetPassword_orPublishEvent() {
        // Already approved, previously deactivated, user has since chosen
        // their own password (mustChangePassword=false). Re-activating must
        // not rotate the credential and must not publish a delivery event.
        Fixture f = new Fixture();
        User user = User.builder().id(2L).email("c@d.com").password("user-chosen")
                .active(false).approved(true).mustChangePassword(false).build();
        when(f.userRepo.findById(2L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = f.service.setActive(2L, true);

        assertTrue(result.isActive());
        assertEquals("user-chosen", result.getPassword());
        assertFalse(result.isMustChangePassword());
        verify(f.encoder, never()).encode(any());
        verifyNoInteractions(f.publisher);
    }

    // -- markCredentialDelivered (callback for the listener) ------------------

    @Test
    void markCredentialDelivered_setsTimestamp_onSave() {
        Fixture f = new Fixture();
        User user = User.builder().id(99L).active(true).approved(true).build();
        when(f.userRepo.findById(99L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.markCredentialDelivered(99L);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(f.userRepo).save(cap.capture());
        assertNotNull(cap.getValue().getCredentialDeliveredAt());
    }

    @Test
    void markCredentialDelivered_noOp_whenUserGone() {
        // User deleted between event publish and listener callback — log and
        // move on, don't throw and crash the listener thread.
        Fixture f = new Fixture();
        when(f.userRepo.findById(404L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> f.service.markCredentialDelivered(404L));
        verify(f.userRepo, never()).save(any());
    }

    // -- Deactivation (still inline, with TODO marker) -----------------------

    @Test
    void deactivation_notifiesByEmail_swiftInnBrandForSystemUser() {
        Fixture f = new Fixture();
        User user = User.builder().id(12L).email("staff@acme.co.zw").firstName("Tendai")
                .roles(java.util.EnumSet.of(User.Role.SHOP_ADMIN))
                .active(true).approved(true).password("pw").build();
        when(f.userRepo.findById(12L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.setActive(12L, false);

        verify(f.email).sendEmail(eq("staff@acme.co.zw"), contains("deactivated"),
                contains("SwiftInn"), startsWith("ACCOUNT-DEACTIVATED-"));
        // Deactivation must never publish a credential-delivery event.
        verifyNoInteractions(f.publisher);
    }

    @Test
    void deactivation_ofApprovedUser_leavesPasswordUntouched() {
        Fixture f = new Fixture();
        User user = User.builder().id(4L).active(true).approved(true).password("pw").build();
        when(f.userRepo.findById(4L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = f.service.setActive(4L, false);

        assertFalse(result.isActive());
        assertEquals("pw", result.getPassword());
        verify(f.encoder, never()).encode(any());
        verifyNoInteractions(f.publisher);
    }

    // -- Reset temp password (admin recovery) --------------------------------

    @Test
    void resetTemporaryPassword_rotatesFlagsChange_clearsDeliveredAt_andPublishesResetEvent() {
        Fixture f = new Fixture();
        User user = User.builder().id(42L).email("alice@innbucks.co.zw").phoneNumber("+263771234567")
                .password("old-hash").active(true).approved(true).mustChangePassword(false)
                .credentialDeliveredAt(LocalDateTime.now().minusDays(1))
                .roles(java.util.EnumSet.of(User.Role.EVENT_ORGANIZER)).build();
        when(f.userRepo.findById(42L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.encoder.encode(anyString())).thenReturn("new-hash");

        User result = f.service.resetTemporaryPassword(42L, "admin@innbucks.co.zw", AuditContext.none());

        assertEquals("new-hash", result.getPassword());
        assertTrue(result.isMustChangePassword());            // single-use again
        assertNull(result.getCredentialDeliveredAt());        // cleared so re-delivery resets it cleanly
        String generated = capturePassword(f.encoder);
        assertNotEquals("#Pass123", generated);
        assertTrue(generated.matches(TEMP_PW_SHAPE));

        CredentialDeliveryRequested ev = captureEvent(f.publisher);
        assertEquals(generated, ev.tempPassword());
        assertEquals(CredentialDeliveryRequested.Reason.RESET, ev.reason());
        verify(f.audit).recordSuccess(
                eq(AuditEventType.USER_TEMP_PASSWORD_RESET),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("42"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(), eq(AuditContext.none()));
    }

    @Test
    void resetTemporaryPassword_refusesSuperAdminTarget() {
        Fixture f = new Fixture();
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(true).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(f.userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.resetTemporaryPassword(1L, "admin@innbucks.co.zw", AuditContext.none()));

        assertThat(ex.getReason()).contains("SUPER_ADMIN");
        verify(f.encoder, never()).encode(any());
        verify(f.userRepo, never()).save(any());
        verifyNoInteractions(f.publisher);
    }

    @Test
    void resetTemporaryPassword_throwsNotFoundWhenMissing() {
        Fixture f = new Fixture();
        when(f.userRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class,
                () -> f.service.resetTemporaryPassword(99L, "admin@innbucks.co.zw", AuditContext.none()));
    }

    // -- SUPER_ADMIN protection ----------------------------------------------

    @Test
    void setActive_refusesSuperAdminTarget_403() {
        Fixture f = new Fixture();
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(true).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(f.userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setActive(1L, /* active */ false, "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertThat(ex.getReason()).contains("SUPER_ADMIN");
        verify(f.userRepo, never()).save(any());
        verify(f.audit, never()).recordSuccess(any(), anyString(), anyString(), anyString(), anyString(),
                any(), any());
        verifyNoInteractions(f.publisher);
    }

    @Test
    void setActive_refusesSuperAdminTarget_evenWhenReactivating() {
        Fixture f = new Fixture();
        User superAdmin = User.builder().id(1L).email("admin@innbucks.co.zw")
                .password("pw").active(false).approved(true)
                .roles(java.util.EnumSet.of(User.Role.SUPER_ADMIN)).build();
        when(f.userRepo.findById(1L)).thenReturn(Optional.of(superAdmin));

        assertThrows(ResponseStatusException.class,
                () -> f.service.setActive(1L, /* active */ true, "admin@innbucks.co.zw", AuditContext.none()));
        verify(f.userRepo, never()).save(any());
    }

    @Test
    void throwsNotFound_whenUserMissing() {
        Fixture f = new Fixture();
        when(f.userRepo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> f.service.setActive(99L, true));
    }

    // -- Audit ----------------------------------------------------------------

    @Test
    void firstActivation_recordsUSER_APPROVED_withAdminAsActor() {
        Fixture f = new Fixture();
        User user = User.builder().id(7L).email("merchant@acme.co.zw").phoneNumber("+263771234567")
                .password("placeholder").active(false).approved(false).build();
        when(f.userRepo.findById(7L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(f.encoder.encode(anyString())).thenReturn("encoded-temp");

        AuditContext ctx = new AuditContext("203.0.113.5", "curl/8.4.0");
        f.service.setActive(7L, true, "admin@innbucks.co.zw", ctx);

        verify(f.audit).recordSuccess(
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
        Fixture f = new Fixture();
        User user = User.builder().id(8L).email("staff@acme.co.zw")
                .password("user-chosen").active(false).approved(true).mustChangePassword(false).build();
        when(f.userRepo.findById(8L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.setActive(8L, true, "admin@innbucks.co.zw", AuditContext.none());

        verify(f.audit).recordSuccess(
                eq(AuditEventType.USER_ACTIVATED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("8"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(),
                eq(AuditContext.none()));
    }

    @Test
    void deactivation_recordsUSER_DEACTIVATED() {
        Fixture f = new Fixture();
        User user = User.builder().id(9L).email("staff@acme.co.zw")
                .password("pw").active(true).approved(true).build();
        when(f.userRepo.findById(9L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.setActive(9L, false, "admin@innbucks.co.zw", AuditContext.none());

        verify(f.audit).recordSuccess(
                eq(AuditEventType.USER_DEACTIVATED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("9"), eq(AuditService.TARGET_TYPE_USER),
                argThat(metadata -> Boolean.FALSE.equals(metadata.get("active"))),
                eq(AuditContext.none()));
    }

    @Test
    void noOpIdempotentRetry_recordsNoAudit() {
        Fixture f = new Fixture();
        User user = User.builder().id(10L).active(true).approved(true)
                .mustChangePassword(true).credentialDeliveredAt(LocalDateTime.now())
                .password("pw").build();
        when(f.userRepo.findById(10L)).thenReturn(Optional.of(user));

        f.service.setActive(10L, true, "admin@innbucks.co.zw", AuditContext.none());

        verifyNoInteractions(f.audit);
    }

    @Test
    void noArgOverload_recordsSystemActor_forBackwardCompat() {
        Fixture f = new Fixture();
        User user = User.builder().id(11L).active(false).approved(true).password("pw").build();
        when(f.userRepo.findById(11L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.setActive(11L, true);

        verify(f.audit).recordSuccess(
                eq(AuditEventType.USER_ACTIVATED),
                eq("system"), eq(AuditService.ACTOR_TYPE_SYSTEM),
                eq("11"), eq(AuditService.TARGET_TYPE_USER),
                anyMap(),
                eq(AuditContext.none()));
    }
}
