package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.SupportMfaResetNotice;
import com.innbucks.userservice.exception.AuditUnavailableException;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.PasswordResetService;
import com.innbucks.userservice.support.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.support.dto.SupportDTOs.WriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The console support writes, step by step (design §3.1.5): permission →
 * lookup binding → self-action → staff targets → idempotency → act → seal →
 * what happens next. The order is part of the security, so the tests pin it:
 * a refusal at one step must leave every later step untouched.
 */
class ConsoleSupportActionsTest {

    private static final UUID AGENT_UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f");
    private static final String KEY = "3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d";
    private static final Long TARGET = 1042L;

    private SupportLookupBinding binding;
    private SupportStaffTargets staffTargets;
    private SupportActionRepository actions;
    private SupportAccessLogWriter accessLog;
    private ConsoleSupportSection console;
    private UserRepository users;
    private OrganizationMemberRepository members;
    private OrganizationRepository organizations;
    private MfaService mfaService;
    private PasswordResetService passwordReset;
    private AuditService audit;
    private ApplicationEventPublisher events;
    private PlatformTransactionManager tm;
    private ConsoleSupportActions subject;
    private User target;

    @BeforeEach
    void setUp() {
        binding = mock(SupportLookupBinding.class);
        staffTargets = mock(SupportStaffTargets.class);
        actions = mock(SupportActionRepository.class);
        accessLog = mock(SupportAccessLogWriter.class);
        console = mock(ConsoleSupportSection.class);
        users = mock(UserRepository.class);
        members = mock(OrganizationMemberRepository.class);
        organizations = mock(OrganizationRepository.class);
        mfaService = mock(MfaService.class);
        passwordReset = mock(PasswordResetService.class);
        audit = mock(AuditService.class);
        events = mock(ApplicationEventPublisher.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        SupportProperties props = new SupportProperties();
        props.afterPropertiesSet();
        SupportProperties.Limiter generous = new SupportProperties.Limiter();
        generous.setShortWindowMax(1000);
        generous.setDailyMax(1000);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, generous, SupportMetrics.none(), () -> 0L);
        subject = new ConsoleSupportActions(limiter, binding, staffTargets, actions, accessLog, SupportMetrics.none(),
                console, users, members, organizations, mfaService, passwordReset, audit, events,
                props, tm, Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));

        target = User.builder().id(TARGET).userUuid(UUID.randomUUID()).firstName("Tariro").lastName("Moyo")
                .email("tariro@example.com").phoneNumber("+263771234567")
                .roles(new LinkedHashSet<>(List.of("MERCHANT_ADMIN"))).active(true).approved(true)
                .failedLoginAttempts(5).lockedUntil(Instant.parse("2026-09-30T10:20:00Z"))
                .mfaEnabled(true).mfaSecret("secret").build();
        when(users.lockById(TARGET)).thenReturn(Optional.of(target));
        when(users.findById(TARGET)).thenReturn(Optional.of(target));
        when(actions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of());
        when(passwordReset.requestResetForSupport(any(), any())).thenReturn(true);
        bound(new SupportCustomerKeys(List.of("+263771234567"), List.of("tariro@example.com"),
                List.of(target.getUserUuid().toString()), List.of(TARGET), null));
    }

    private void bound(SupportCustomerKeys keys) {
        when(binding.bind(any(), eq("SLK-7Q2M9X"), eq("console"), eq("1042")))
                .thenReturn(new SupportLookupBinding.Bound("SLK-7Q2M9X", keys, List.of("console"),
                        Map.of("console", List.of("1042")), LocalDateTime.now(ZoneOffset.UTC)));
    }

    private static SupportAgent agent(String... authorities) {
        return new SupportAgent("agent.one@innbucks.co.zw", 7L, AGENT_UUID, "agent.one@innbucks.co.zw", null,
                "+263772000001", Set.of(authorities));
    }

    private static SupportAgent agentWithManage() {
        return agent(PermissionCatalog.SUPPORT_CONSOLE_READ, PermissionCatalog.SUPPORT_CONSOLE_MANAGE);
    }

    private static WriteRequest body() {
        return new WriteRequest("SLK-7Q2M9X", "Caller verified by date of birth.", null);
    }

    private ActionResult run(ConsoleSupportActions.Op op, SupportAgent agent, String key) {
        return subject.execute(op, agent, TARGET, body(), key, new AuditContext("41.221.147.12", "console"));
    }

    private static String code(Throwable e) {
        return ((SupportPolicyException) e).getErrorCode();
    }

    // ---- 1. permission ----------------------------------------------------------------------

    @Test
    @DisplayName("1: the MFA reset re-checks its supervisor code in the service; nothing else runs")
    void mfaResetNeedsItsOwnCode() {
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.MFA_RESET, agentWithManage(), KEY))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(binding, staffTargets, actions, mfaService, audit);
    }

    // ---- 2. binding -------------------------------------------------------------------------

    @Test
    @DisplayName("2: a binding refusal stops everything after it, and is access-logged")
    void bindingRefusalStopsEverything() {
        when(binding.bind(any(), any(), any(), any())).thenThrow(SupportPolicyException.lookupExpired());
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("lookup_expired"));
        verifyNoInteractions(staffTargets, actions, mfaService, audit);
        verify(users, never()).lockById(any());
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOutcome()).isEqualTo("lookup_expired");
        assertThat(row.getValue().getOp()).isEqualTo("CONSOLE_UNLOCK");
    }

    // ---- 3. self ----------------------------------------------------------------------------

    @Test
    @DisplayName("3: the agent's own contact phone in the lookup is 403 support_self_action, audited as a refusal")
    void selfAction() {
        bound(new SupportCustomerKeys(List.of("+263772000001"), List.of(), List.of(), List.of(TARGET), null));
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_self_action"));
        verify(audit).recordFailure(eq(AuditEventType.SUPPORT_ACTION_REFUSED), eq("agent.one@innbucks.co.zw"),
                anyString(), eq("1042"), anyString(), eq("support_self_action"), any(), any());
        verifyNoInteractions(staffTargets, actions);
        verify(users, never()).lockById(any());
    }

    @Test
    @DisplayName("3: the agent's own account id as the target is self-action too")
    void selfById() {
        SupportAgent me = new SupportAgent("agent.one@innbucks.co.zw", TARGET, AGENT_UUID, "agent.one@innbucks.co.zw",
                null, null, Set.of(PermissionCatalog.SUPPORT_CONSOLE_MANAGE));
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, me, KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_self_action"));
    }

    // ---- 4. staff targets -------------------------------------------------------------------

    @Test
    @DisplayName("4: a lookup reaching a staff account needs support-staff-targets:manage — an agent is refused")
    void staffTargetNeedsSupervisor() {
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("staff_target_requires_supervisor"));
        verifyNoInteractions(actions);
        verify(users, never()).lockById(any());
    }

    @Test
    @DisplayName("4: a supervisor passes the staff check, and the console guard then refuses a staff target outright")
    void supervisorStillCannotTouchAStaffAccount() {
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(target.getUserUuid()));
        when(staffTargets.isStaffAccount(target)).thenReturn(true);
        SupportAgent supervisor = agent(PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, supervisor, KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
        verify(actions, never()).saveAndFlush(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("4: SUPER_ADMIN is refused as a console target even when nothing else marks it staff")
    void superAdminIsNeverATarget() {
        target.setRoles(new LinkedHashSet<>(List.of("SUPER_ADMIN")));
        SupportAgent supervisor = agent(PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, supervisor, KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
    }

    // ---- 5. idempotency ---------------------------------------------------------------------

    @Test
    @DisplayName("5: no Idempotency-Key (or not a UUID) is 400 idempotency_key_required — after the earlier checks")
    void idempotencyKeyRequired() {
        for (String bad : new String[] {null, "", "not-a-uuid", "3f6c1a52"}) {
            assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), bad))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("idempotency_key_required"));
        }
        verify(users, never()).lockById(any());
        verify(staffTargets, org.mockito.Mockito.atLeastOnce()).staffAccounts(any());
    }

    @Test
    @DisplayName("5: a repeated key returns the stored outcome with replayed=true and acts nothing twice")
    void replay() {
        when(actions.findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY))).thenReturn(Optional.of(
                SupportAction.builder().op("CONSOLE_UNLOCK").target("1042").outcome("SUCCESS")
                        .whatHappensNext(ConsoleSupportActions.UNLOCKED_NEXT).build()));
        ActionResult r = run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY);
        assertThat(r.replayed()).isTrue();
        assertThat(r.outcome()).isEqualTo("SUCCESS");
        assertThat(r.whatHappensNext()).isEqualTo(ConsoleSupportActions.UNLOCKED_NEXT);
        verify(users, never()).lockById(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("5: the same key for a DIFFERENT action is 409 idempotency_key_reused")
    void reusedKey() {
        when(actions.findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY))).thenReturn(Optional.of(
                SupportAction.builder().op("CONSOLE_SEND_PASSWORD_RESET").target("1042").outcome("SUCCESS").build()));
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("idempotency_key_reused"));
        verify(users, never()).lockById(any());
    }

    @Test
    @DisplayName("5: a concurrent repeat loses the unique index and is answered from the first request's row")
    void concurrentRepeat() {
        SupportAction first = SupportAction.builder().op("CONSOLE_UNLOCK").target("1042").outcome("SUCCESS")
                .whatHappensNext(ConsoleSupportActions.UNLOCKED_NEXT).build();
        when(actions.findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY)))
                .thenReturn(Optional.empty(), Optional.of(first));
        when(actions.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_support_actions_agent_key"));
        ActionResult r = run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY);
        assertThat(r.replayed()).isTrue();
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- 6-8. act, seal, tell ----------------------------------------------------------------

    @Test
    @DisplayName("6-8: unlock clears both lockouts, then seals LAST (fail-closed), then says what happens next")
    void unlockHappyPathInOrder() {
        ActionResult r = run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY);

        assertThat(r.outcome()).isEqualTo("SUCCESS");
        assertThat(r.replayed()).isFalse();
        assertThat(r.whatHappensNext()).isEqualTo(ConsoleSupportActions.UNLOCKED_NEXT);
        assertThat(target.getFailedLoginAttempts()).isZero();
        assertThat(target.getLockedUntil()).isNull();
        assertThat(target.getMfaFailedAttempts()).isZero();
        assertThat(target.getMfaLockedUntil()).isNull();

        InOrder order = inOrder(binding, staffTargets, actions, users, audit);
        order.verify(binding).bind(any(), eq("SLK-7Q2M9X"), eq("console"), eq("1042"));
        order.verify(staffTargets).staffAccounts(any());
        order.verify(actions).findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY));
        order.verify(users).lockById(TARGET);
        order.verify(actions).saveAndFlush(any());   // PENDING: the idempotency lock
        order.verify(users).saveAndFlush(target);
        order.verify(actions).saveAndFlush(any());   // SUCCESS + whatHappensNext
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        order.verify(audit).recordRequired(eq(AuditEventType.SUPPORT_CONSOLE_ACCOUNT_UNLOCKED),
                eq("agent.one@innbucks.co.zw"), eq(AuditService.ACTOR_TYPE_USER), eq("1042"),
                eq(AuditService.TARGET_TYPE_USER), meta.capture(), any());
        assertThat(meta.getValue())
                .containsEntry("customerKey", "msisdn:****4567")
                .containsEntry("lookupId", "SLK-7Q2M9X")
                .containsEntry("idempotencyKey", KEY)
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("note", "Caller verified by date of birth.")
                .containsEntry("wasLocked", true);
        // The seal names the customer masked — never the whole number or address.
        assertThat(meta.getValue().values().toString()).doesNotContain("+263771234567", "tariro@example.com");
    }

    @Test
    @DisplayName("7: a seal that cannot be written refuses the change (503 audit_unavailable propagates)")
    void sealFailureRefuses() {
        doThrow(new AuditUnavailableException(new RuntimeException("db")))
                .when(audit).recordRequired(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY))
                .isInstanceOf(AuditUnavailableException.class);
        // Fail-CLOSED means the transaction holding the change is ROLLED BACK — never committed.
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
        verify(accessLog, never()).bestEffort(org.mockito.ArgumentMatchers.argThat(
                row -> row != null && "OK".equals(row.getOutcome())));
    }

    @Test
    @DisplayName("send-password-reset passes ONLY the account's email to PasswordResetService")
    void resetUsesTheEmailOnly() {
        ActionResult r = run(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, agentWithManage(), KEY);
        verify(passwordReset).requestResetForSupport(eq("tariro@example.com"), any());
        verify(passwordReset, never()).requestReset(any(), any());
        // The email goes AFTER commit: the sentence says it is on its way, never that it arrived.
        assertThat(r.whatHappensNext()).isEqualTo("We're emailing a password-reset code to tariro@example.com now. It "
                + "works for 5 minutes: ask the caller to choose Forgot password on the Foundry sign-in page, enter "
                + "this email, then the code. If it hasn't arrived within a couple of minutes, ask them to check "
                + "their spam folder before you send another.");
        verify(audit).recordRequired(eq(AuditEventType.SUPPORT_CONSOLE_PASSWORD_RESET_SENT), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("send-password-reset refuses an inactive or unapproved account, or one with no email, before sending")
    void resetRefusals() {
        target.setActive(false);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("account_inactive"));
        target.setActive(true);
        target.setApproved(false);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("account_inactive"));
        target.setApproved(true);
        target.setEmail(null);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("no_email_on_account"));
        verifyNoInteractions(passwordReset);
    }

    @Test
    @DisplayName("send-password-reset maps the OTP quota to 429 reset_code_limited")
    void resetQuota() {
        when(passwordReset.requestResetForSupport(any(), any())).thenThrow(new OtpService.OtpRateLimitException("quota"));
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.SEND_PASSWORD_RESET, agentWithManage(), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("reset_code_limited"));
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("mfa/reset: wipes through MfaService, notifies every OTHER owner, and seals SUPPORT_CONSOLE_MFA_RESET")
    void mfaReset() {
        UUID org = UUID.randomUUID();
        when(members.findByUserId(TARGET)).thenReturn(List.of(
                OrganizationMember.builder().organizationId(org).userId(TARGET).role(OrganizationMember.Role.ADMIN).build()));
        when(organizations.findAllById(List.of(org))).thenReturn(List.of(
                Organization.builder().id(org).name("Moyo Fresh Foods").build()));
        when(members.findByOrganizationIdInAndRole(List.of(org), OrganizationMember.Role.OWNER)).thenReturn(List.of(
                OrganizationMember.builder().organizationId(org).userId(9L).role(OrganizationMember.Role.OWNER).build(),
                OrganizationMember.builder().organizationId(org).userId(TARGET).role(OrganizationMember.Role.OWNER).build()));
        when(users.findAllById(List.of(9L))).thenReturn(List.of(User.builder().id(9L).email("Owner@Example.com").build()));
        when(mfaService.resetForSupport(target)).thenReturn(8L);

        SupportAgent supervisor = agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET);
        ActionResult r = run(ConsoleSupportActions.Op.MFA_RESET, supervisor, KEY);

        assertThat(r.whatHappensNext()).isEqualTo(ConsoleSupportActions.MFA_RESET_NEXT);
        verify(mfaService).resetForSupport(target);
        ArgumentCaptor<SupportMfaResetNotice> notice = ArgumentCaptor.forClass(SupportMfaResetNotice.class);
        verify(events).publishEvent(notice.capture());
        assertThat(notice.getValue().owners()).containsExactly(
                new SupportMfaResetNotice.OwnerNotice("owner@example.com", List.of("Moyo Fresh Foods")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(eq(AuditEventType.SUPPORT_CONSOLE_MFA_RESET), any(), any(), eq("1042"), any(),
                meta.capture(), any());
        assertThat(meta.getValue()).containsEntry("tokenVersion", 8L).containsEntry("ownersNotified", 1);
    }

    @Test
    @DisplayName("C1: each owner is told ONLY about the businesses they own — never the account's other businesses")
    void ownersAreGroupedByTheirOwnBusinesses() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(members.findByUserId(TARGET)).thenReturn(List.of(
                OrganizationMember.builder().organizationId(a).userId(TARGET).role(OrganizationMember.Role.STAFF).build(),
                OrganizationMember.builder().organizationId(b).userId(TARGET).role(OrganizationMember.Role.ADMIN).build()));
        when(organizations.findAllById(List.of(a, b))).thenReturn(List.of(
                Organization.builder().id(a).name("Alpha Foods").build(),
                Organization.builder().id(b).name("Beta Hardware").build()));
        when(members.findByOrganizationIdInAndRole(List.of(a, b), OrganizationMember.Role.OWNER)).thenReturn(List.of(
                OrganizationMember.builder().organizationId(a).userId(20L).role(OrganizationMember.Role.OWNER).build(),
                OrganizationMember.builder().organizationId(b).userId(30L).role(OrganizationMember.Role.OWNER).build(),
                // One person owning both, under two rows: one notice, both names.
                OrganizationMember.builder().organizationId(a).userId(40L).role(OrganizationMember.Role.OWNER).build(),
                OrganizationMember.builder().organizationId(b).userId(40L).role(OrganizationMember.Role.OWNER).build(),
                // An owner sharing the account's own address is never a separate recipient.
                OrganizationMember.builder().organizationId(b).userId(50L).role(OrganizationMember.Role.OWNER).build()));
        when(users.findAllById(any())).thenReturn(List.of(
                User.builder().id(20L).email("alpha.owner@example.com").build(),
                User.builder().id(30L).email("beta.owner@example.com").build(),
                User.builder().id(40L).email("Both@Example.com").build(),
                User.builder().id(50L).email("TARIRO@example.com").build()));
        when(mfaService.resetForSupport(target)).thenReturn(8L);

        run(ConsoleSupportActions.Op.MFA_RESET, agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET), KEY);

        ArgumentCaptor<SupportMfaResetNotice> notice = ArgumentCaptor.forClass(SupportMfaResetNotice.class);
        verify(events).publishEvent(notice.capture());
        assertThat(notice.getValue().owners()).containsExactly(
                new SupportMfaResetNotice.OwnerNotice("alpha.owner@example.com", List.of("Alpha Foods")),
                new SupportMfaResetNotice.OwnerNotice("beta.owner@example.com", List.of("Beta Hardware")),
                new SupportMfaResetNotice.OwnerNotice("both@example.com", List.of("Alpha Foods", "Beta Hardware")));
    }

    @Test
    @DisplayName("C8: with no other owner to tell, nothing is published and the agent isn't told owners were emailed")
    void mfaResetWithNoOwners() {
        when(members.findByUserId(TARGET)).thenReturn(List.of());
        when(mfaService.resetForSupport(target)).thenReturn(8L);
        ActionResult r = run(ConsoleSupportActions.Op.MFA_RESET, agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET), KEY);
        assertThat(r.whatHappensNext()).isEqualTo(ConsoleSupportActions.MFA_RESET_NEXT_NO_OWNERS)
                .doesNotContain("emailed the other owners");
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("mfa/reset: an account without a second factor is 409 mfa_not_enrolled, nothing wiped")
    void mfaNotEnrolled() {
        target.setMfaEnabled(false);
        target.setMfaSecret(null);
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.MFA_RESET,
                agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("mfa_not_enrolled"));
        verifyNoInteractions(mfaService);
    }

    @Test
    @DisplayName("S5: an account that became STAFF after the search is refused on the write's fresh read — nothing wiped")
    void accountBecameStaffAfterTheSearch() {
        // The lookup was taken while it was a merchant; now it holds a staff role.
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(target.getUserUuid()));
        when(staffTargets.isStaffAccount(target)).thenReturn(true);

        // An agent without support-staff-targets:manage stops at step 4 …
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.MFA_RESET,
                agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("staff_target_requires_supervisor"));
        // … and a supervisor who holds it is refused by the fresh read of the account itself.
        assertThatThrownBy(() -> run(ConsoleSupportActions.Op.MFA_RESET,
                agent(PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET, PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
        verifyNoInteractions(mfaService);
        verify(actions, never()).saveAndFlush(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
        verify(audit, org.mockito.Mockito.times(2)).recordFailure(eq(AuditEventType.SUPPORT_ACTION_REFUSED), any(),
                any(), eq("1042"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S2: unlock and mfa/reset refuse a deactivated or pending account — 409 account_inactive, nothing changed")
    void unlockAndMfaResetNeedAnActiveApprovedAccount() {
        SupportAgent supervisor = agent(PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET);
        for (boolean[] state : new boolean[][] {{false, true}, {true, false}}) {
            target.setActive(state[0]);
            target.setApproved(state[1]);
            for (ConsoleSupportActions.Op op : List.of(ConsoleSupportActions.Op.UNLOCK,
                    ConsoleSupportActions.Op.MFA_RESET)) {
                assertThatThrownBy(() -> run(op, supervisor, KEY))
                        .satisfies(e -> assertThat(code(e)).isEqualTo("account_inactive"));
            }
        }
        assertThat(target.getLockedUntil()).isNotNull();
        verifyNoInteractions(mfaService);
        verify(actions, never()).saveAndFlush(any());
        verify(users, never()).saveAndFlush(any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S3: a note that is empty once markup is stripped is 400 note_required — before the limiter or anything else")
    void noteEmptyOnceCleanedIsRefused() {
        SupportProperties props = new SupportProperties();
        props.afterPropertiesSet();
        SupportProperties.Limiter one = new SupportProperties.Limiter();
        one.setShortWindowMax(1);
        one.setDailyMax(1);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, one, SupportMetrics.none(), () -> 0L);
        ConsoleSupportActions tight = new ConsoleSupportActions(limiter, binding, staffTargets, actions, accessLog,
                SupportMetrics.none(), console, users, members, organizations, mfaService, passwordReset, audit, events,
                props, tm, Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC));
        for (String note : new String[] {"<b></b>", "<script>alert(1)</script>", "\u202E\u200B"}) {
            assertThatThrownBy(() -> tight.execute(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), TARGET,
                    new WriteRequest("SLK-7Q2M9X", note, null), KEY, AuditContext.none()))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("note_required"));
        }
        verifyNoInteractions(binding, staffTargets, actions, audit, accessLog);
        // The limiter was never touched: the one slot is still there.
        tight.execute(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), TARGET, body(), KEY, AuditContext.none());
    }

    @Test
    @DisplayName("S3: the note is sealed CLEANED — markup gone, never longer than the 500 the request allows")
    void noteIsSealedCleaned() {
        subject.execute(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), TARGET,
                new WriteRequest("SLK-7Q2M9X", "Caller <b>verified</b> by DOB.", null), KEY, AuditContext.none());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordRequired(any(), any(), any(), any(), any(), meta.capture(), any());
        assertThat(meta.getValue()).containsEntry("note", "Caller verified by DOB.");
    }

    // ---- detail ------------------------------------------------------------------------------

    @Test
    @DisplayName("T4: a detail read whose access-log row can't be written is 503 support_log_unavailable — no view leaves")
    void detailLogFailureRefuses() {
        doThrow(SupportPolicyException.logUnavailable()).when(accessLog).required(any());
        when(binding.bind(any(), eq("SLK-7Q2M9X"), eq("console"), eq("1042"))).thenReturn(
                new SupportLookupBinding.Bound("SLK-7Q2M9X", SupportCustomerKeys.empty(), List.of("console"),
                        Map.of("console", List.of("1042")), LocalDateTime.now(ZoneOffset.UTC)));
        assertThatThrownBy(() -> subject.detail(agentWithManage(), TARGET, "SLK-7Q2M9X", "41.221.147.12"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_log_unavailable"));
        // The view was built only to be discarded; the point is that nothing returned it.
        verify(accessLog).required(any());
    }

    @Test
    @DisplayName("C7: a detail read of an account gone since the search is 404 target_not_found, and access-logged")
    void detailTargetGoneIsLogged() {
        when(users.findById(TARGET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> subject.detail(agentWithManage(), TARGET, "SLK-7Q2M9X", "41.221.147.12"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("target_not_found"));
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOutcome()).isEqualTo("target_not_found");
        assertThat(row.getValue().getOp()).isEqualTo("CONSOLE_DETAIL");
        assertThat(row.getValue().getLookupId()).isEqualTo("SLK-7Q2M9X");
        verify(accessLog, never()).required(any());
    }

    @Test
    @DisplayName("S1: a detail read of an account that became STAFF since the search is 403 — never the full view")
    void detailOfANewStaffAccountIsRefused() {
        when(staffTargets.isStaffAccount(target)).thenReturn(true);
        assertThatThrownBy(() -> subject.detail(agentWithManage(), TARGET, "SLK-7Q2M9X", null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
        verify(console, never()).view(any(), any(), any());
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOutcome()).isEqualTo("console_staff_account");
    }

    @Test
    @DisplayName("C5: the access-log row's agent subject is bounded to the column (VARCHAR 254)")
    void agentSubjectIsBounded() {
        String longSubject = "a".repeat(300) + "@innbucks.co.zw";
        SupportAgent agent = new SupportAgent(longSubject, 7L, AGENT_UUID, longSubject, null, null,
                Set.of(PermissionCatalog.SUPPORT_CONSOLE_MANAGE));
        subject.execute(ConsoleSupportActions.Op.UNLOCK, agent, TARGET, body(), KEY, AuditContext.none());
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getAgentSubject()).hasSize(254);
    }

    @Test
    @DisplayName("unlock on an account that isn't locked changes nothing, says so, and is still sealed")
    void unlockNotLocked() {
        target.setFailedLoginAttempts(0);
        target.setLockedUntil(null);
        ActionResult r = run(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), KEY);
        assertThat(r.whatHappensNext()).isEqualTo(ConsoleSupportActions.NOT_LOCKED_NEXT);
        verify(users, never()).saveAndFlush(any());
        verify(audit).recordRequired(eq(AuditEventType.SUPPORT_CONSOLE_ACCOUNT_UNLOCKED), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("support switched off: 404 support_disabled before anything else")
    void disabled() {
        SupportProperties off = new SupportProperties();
        off.setEnabled(false);
        off.afterPropertiesSet();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        ConsoleSupportActions offSubject = new ConsoleSupportActions(
                new SupportLookupLimiter(null, new SupportProperties.Limiter(), SupportMetrics.none(), () -> 0L),
                binding, staffTargets, actions, accessLog, SupportMetrics.none(), console, users, members,
                organizations, mfaService, passwordReset, audit, events, off, tm, Clock.systemUTC());
        assertThatThrownBy(() -> offSubject.execute(ConsoleSupportActions.Op.UNLOCK, agentWithManage(), TARGET, body(),
                KEY, AuditContext.none()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_disabled"));
        verifyNoInteractions(binding);
    }
}
