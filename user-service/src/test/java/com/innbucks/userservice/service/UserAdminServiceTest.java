package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.CredentialDeliveryRequested;
import com.innbucks.userservice.event.UserDeactivatedEvent;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
        final AuditService audit = mock(AuditService.class);
        final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        // Approval copy names the business, so the service resolves the tenant
        // profile. Default mock -> Optional.empty -> the generic wording, which
        // is what every case in this class asserts.
        final com.innbucks.userservice.repository.TenantProfileRepository tenantProfiles =
                mock(com.innbucks.userservice.repository.TenantProfileRepository.class);
        // Role changes bump token_version and mirror it to the shared Redis so a
        // demotion takes effect fleet-wide immediately; mocked so the assertions
        // can prove the publish happened without a Redis.
        final com.innbucks.userservice.security.TokenVersionPublisher tokenVersions =
                mock(com.innbucks.userservice.security.TokenVersionPublisher.class);
        // setRoles validates every requested name against the roles table
        // (V35). Stubbed to resolve any built-in name, so these tests keep
        // asserting the SUPER_ADMIN / scope guards rather than the new
        // existence check — RoleAdminServiceTest covers that separately.
        final com.innbucks.userservice.repository.RoleRepository roleRepo =
                mock(com.innbucks.userservice.repository.RoleRepository.class);
        {
            when(roleRepo.findAllByNameIn(any())).thenAnswer(inv -> {
                java.util.Collection<String> names = inv.getArgument(0);
                return names.stream()
                        .map(n -> com.innbucks.userservice.entity.Role.builder()
                                .name(n).description(n).builtin(true).build())
                        .toList();
            });
        }
        final UserAdminService service = new UserAdminService(
                userRepo, encoder, audit, publisher, tenantProfiles, tokenVersions, roleRepo);
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
    void markCredentialDelivered_stampsTimestamp_viaTargetedUpdate() {
        Fixture f = new Fixture();
        when(f.userRepo.markCredentialDelivered(eq(99L), any(LocalDateTime.class))).thenReturn(1);

        f.service.markCredentialDelivered(99L);

        ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(f.userRepo).markCredentialDelivered(eq(99L), at.capture());
        assertNotNull(at.getValue());
    }

    @Test
    void markCredentialDelivered_neverLoadsAndResavesTheEntity() {
        // Regression guard for the lost update this replaced. The old body was
        // findById -> setter -> save, which ran on the @Async listener thread an
        // unbounded time after the activation committed. User carries no
        // @DynamicUpdate, so that save rewrote EVERY column from a stale
        // snapshot — silently reverting any password change or admin edit made
        // in the window. It surfaced as a flaky AuthControllerIT login 400.
        //
        // Asserting "no read, no entity save" is the point: a future refactor
        // back to a load-mutate-save would reintroduce the race, and only this
        // shape assertion catches that deterministically. Racing threads in a
        // unit test would just be flaky in the other direction.
        Fixture f = new Fixture();
        when(f.userRepo.markCredentialDelivered(eq(7L), any(LocalDateTime.class))).thenReturn(1);

        f.service.markCredentialDelivered(7L);

        verify(f.userRepo, never()).findById(any());
        verify(f.userRepo, never()).save(any());
    }

    @Test
    void markCredentialDelivered_noOp_whenUserGone() {
        // User deleted between event publish and listener callback — log and
        // move on, don't throw and crash the listener thread. The targeted
        // UPDATE reports this as 0 rows affected rather than an empty Optional.
        Fixture f = new Fixture();
        when(f.userRepo.markCredentialDelivered(eq(404L), any(LocalDateTime.class))).thenReturn(0);

        assertDoesNotThrow(() -> f.service.markCredentialDelivered(404L));
        verify(f.userRepo, never()).save(any());
    }

    // -- Deactivation (still inline, with TODO marker) -----------------------

    @Test
    void deactivation_publishesUserDeactivatedEvent_offTheRequestThread() {
        Fixture f = new Fixture();
        User user = User.builder().id(12L).email("staff@acme.co.zw").firstName("Tendai")
                .phoneNumber("+263771234567")
                .roles(User.roleNames(User.Role.SHOP_ADMIN))
                .active(true).approved(true).password("pw").build();
        when(f.userRepo.findById(12L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        f.service.setActive(12L, false);

        // Delivered off-thread now: the service publishes a UserDeactivatedEvent
        // (handled by AccountSecurityNotificationListener) instead of calling the
        // email gateway inline, so the deactivate response never blocks on it.
        ArgumentCaptor<UserDeactivatedEvent> ev = ArgumentCaptor.forClass(UserDeactivatedEvent.class);
        verify(f.publisher).publishEvent(ev.capture());
        assertEquals(12L, ev.getValue().userId());
        assertEquals("staff@acme.co.zw", ev.getValue().email());
        assertFalse(ev.getValue().customer());   // SHOP_ADMIN => system user => InnBucks Foundry brand
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
        // Deactivation publishes the async notice; the password is untouched.
        verify(f.publisher).publishEvent(any(UserDeactivatedEvent.class));
    }

    // -- Reset temp password (admin recovery) --------------------------------

    @Test
    void resetTemporaryPassword_rotatesFlagsChange_clearsDeliveredAt_andPublishesResetEvent() {
        Fixture f = new Fixture();
        User user = User.builder().id(42L).email("alice@innbucks.co.zw").phoneNumber("+263771234567")
                .password("old-hash").active(true).approved(true).mustChangePassword(false)
                .credentialDeliveredAt(LocalDateTime.now().minusDays(1))
                .roles(User.roleNames(User.Role.EVENT_ORGANIZER)).build();
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
                .roles(User.roleNames(User.Role.SUPER_ADMIN)).build();
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
                .roles(User.roleNames(User.Role.SUPER_ADMIN)).build();
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
                .roles(User.roleNames(User.Role.SUPER_ADMIN)).build();
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

    // -- setRoles -------------------------------------------------------------

    /** A user carrying whatever roles are passed, with a uuid so the
     *  token-version publish has a key to assert on. */
    private static User userWithRoles(long id, User.Role... roles) {
        return User.builder().id(id).userUuid(UUID.randomUUID())
                .email("u" + id + "@innbucks.co.zw").password("pw").active(true).approved(true)
                .roles(User.roleNames(roles))
                .build();
    }

    @Test
    void setRoles_replacesTheWholeSet_notMergesIntoIt() {
        Fixture f = new Fixture();
        User user = userWithRoles(50L, User.Role.CUSTOMER, User.Role.EVENT_ORGANIZER);
        when(f.userRepo.findById(50L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = f.service.setRoles(50L, User.roleNames(User.Role.CUSTOMER),
                "admin@innbucks.co.zw", AuditContext.none());

        // EVENT_ORGANIZER is gone — a merge would have kept it.
        assertThat(result.getRoles()).containsExactly(User.Role.CUSTOMER.name());
    }

    @Test
    void setRoles_bumpsTokenVersion_andPublishesIt_soADemotionTakesEffectImmediately() {
        Fixture f = new Fixture();
        User user = userWithRoles(51L, User.Role.MERCHANT_ADMIN);
        user.setTokenVersion(7);
        when(f.userRepo.findById(51L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = f.service.setRoles(51L, User.roleNames(User.Role.CUSTOMER),
                "admin@innbucks.co.zw", AuditContext.none());

        // Without the bump the demoted user's existing JWT would keep asserting
        // MERCHANT_ADMIN until it expired.
        assertEquals(8, result.getTokenVersion());
        verify(f.tokenVersions).publish(user.getUserUuid(), 8L);
    }

    @Test
    void setRoles_recordsUSER_ROLES_CHANGED_withBothSidesOfTheChange() {
        Fixture f = new Fixture();
        User user = userWithRoles(52L, User.Role.EVENT_ORGANIZER);
        when(f.userRepo.findById(52L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        AuditContext ctx = new AuditContext("10.0.0.9", "curl/8.4");

        f.service.setRoles(52L, User.roleNames(User.Role.CUSTOMER), "admin@innbucks.co.zw", ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(f.audit).recordSuccess(
                eq(AuditEventType.USER_ROLES_CHANGED),
                eq("admin@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER),
                eq("52"), eq(AuditService.TARGET_TYPE_USER),
                meta.capture(),
                eq(ctx));
        assertThat(meta.getValue()).containsEntry("previousRoles", List.of("EVENT_ORGANIZER"));
        assertThat(meta.getValue()).containsEntry("newRoles", List.of("CUSTOMER"));
    }

    @Test
    void setRoles_isANoOp_whenTheSubmittedSetMatchesTheCurrentOne() {
        Fixture f = new Fixture();
        User user = userWithRoles(53L, User.Role.CUSTOMER, User.Role.EVENT_ORGANIZER);
        user.setTokenVersion(3);
        when(f.userRepo.findById(53L)).thenReturn(Optional.of(user));

        User result = f.service.setRoles(53L,
                User.roleNames(User.Role.EVENT_ORGANIZER, User.Role.CUSTOMER),
                "admin@innbucks.co.zw", AuditContext.none());

        // No bump: a UI that PUTs on every save must not log the user out for a
        // change that didn't happen.
        assertEquals(3, result.getTokenVersion());
        verify(f.userRepo, never()).save(any(User.class));
        verifyNoInteractions(f.audit);
        verifyNoInteractions(f.tokenVersions);
    }

    @Test
    void setRoles_refusesSuperAdminTarget_403() {
        Fixture f = new Fixture();
        User owner = userWithRoles(1L, User.Role.SUPER_ADMIN);
        when(f.userRepo.findById(1L)).thenReturn(Optional.of(owner));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setRoles(1L, User.roleNames(User.Role.CUSTOMER),
                        "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(403, ex.getStatusCode().value());
        assertThat(owner.getRoles()).containsExactly(User.Role.SUPER_ADMIN.name());
        verify(f.userRepo, never()).save(any(User.class));
    }

    @Test
    void setRoles_refusesGrantingSuperAdmin_403() {
        Fixture f = new Fixture();
        User user = userWithRoles(54L, User.Role.CUSTOMER);
        when(f.userRepo.findById(54L)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setRoles(54L, User.roleNames(User.Role.SUPER_ADMIN),
                        "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(403, ex.getStatusCode().value());
        verify(f.userRepo, never()).save(any(User.class));
    }

    @Test
    void setRoles_refusesShopRole_whenTheAccountCarriesNoShopScope() {
        Fixture f = new Fixture();
        User user = userWithRoles(55L, User.Role.CUSTOMER);
        when(f.userRepo.findById(55L)).thenReturn(Optional.of(user));

        // This is the "caller's JWT has no shopId" account: it would log in fine
        // and then fail inside every shop-scoped handler.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setRoles(55L, User.roleNames(User.Role.SHOP_USER),
                        "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(400, ex.getStatusCode().value());
        verify(f.userRepo, never()).save(any(User.class));
    }

    @Test
    void setRoles_allowsShopRole_whenTheAccountIsAlreadyScopedToAShop() {
        Fixture f = new Fixture();
        User user = userWithRoles(56L, User.Role.SHOP_USER);
        user.setLoyaltyMerchantId(UUID.randomUUID());
        user.setLoyaltyShopId(UUID.randomUUID());
        when(f.userRepo.findById(56L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = f.service.setRoles(56L, User.roleNames(User.Role.SHOP_ADMIN),
                "admin@innbucks.co.zw", AuditContext.none());

        assertThat(result.getRoles()).containsExactly(User.Role.SHOP_ADMIN.name());
    }

    @Test
    void setRoles_refusesTeamMember_whenTheAccountHasNoParentOrganizer() {
        Fixture f = new Fixture();
        User user = userWithRoles(57L, User.Role.CUSTOMER);
        when(f.userRepo.findById(57L)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setRoles(57L, User.roleNames(User.Role.TEAM_MEMBER),
                        "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(400, ex.getStatusCode().value());
        verify(f.userRepo, never()).save(any(User.class));
    }

    @Test
    void setRoles_rejectsAnEmptyRoleSet_ratherThanBrickingTheAccount() {
        Fixture f = new Fixture();
        User user = userWithRoles(58L, User.Role.CUSTOMER);
        when(f.userRepo.findById(58L)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> f.service.setRoles(58L, User.roleNames(), "admin@innbucks.co.zw", AuditContext.none()));

        assertEquals(400, ex.getStatusCode().value());
        verify(f.userRepo, never()).save(any(User.class));
    }

    @Test
    void setRoles_grantsProductRoles_withNoScopingPrerequisites() {
        Fixture f = new Fixture();
        User user = userWithRoles(60L, User.Role.CUSTOMER);
        when(f.userRepo.findById(60L)).thenReturn(Optional.of(user));
        when(f.userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // Unlike SHOP_* and TEAM_MEMBER, the product roles are platform-side and
        // carry no merchant/shop/organizer scope, so they must be grantable to a
        // plain account without any prior stamping.
        User result = f.service.setRoles(60L,
                User.roleNames(User.Role.PRODUCT_OFFICER, User.Role.PRODUCT_MANAGER),
                "admin@innbucks.co.zw", AuditContext.none());

        assertThat(result.getRoles())
                .containsExactlyInAnyOrder(User.Role.PRODUCT_OFFICER.name(), User.Role.PRODUCT_MANAGER.name());
        // Still a privilege change, so the session must be invalidated like any other.
        assertEquals(1, result.getTokenVersion());
    }

    @Test
    void setRoles_throwsNotFound_whenUserMissing() {
        Fixture f = new Fixture();
        when(f.userRepo.findById(999L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> f.service.setRoles(999L, User.roleNames(User.Role.CUSTOMER),
                        "admin@innbucks.co.zw", AuditContext.none()));
    }
}
