package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BlockRequest;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.AuditUnavailableException;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AccountSessionRevoker;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Op;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Target;
import com.innbucks.userservice.support.InnbucksAppSupportActions.Write;
import com.innbucks.userservice.support.dto.SupportDTOs.AppActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
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
 * The InnBucks app section's support writes, step by step, as for the console:
 * permission → lookup binding (the number must be one the agent's own search
 * returned) → self-action → staff targets → idempotency → act → seal LAST. And
 * "sign out of the Super app": every session ended, every phone blocked, never
 * a staff account.
 */
class InnbucksAppSupportActionsTest {

    private static final UUID AGENT_UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f");
    private static final String KEY = "3f6c1a52-7b0e-4d8a-9c21-5e4f7a8b9c0d";
    private static final String NUMBER = "+263771234567";
    private static final UUID DEVICE = UUID.fromString("0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER_DEVICE = UUID.fromString("6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final String NEXT = "The phone is blocked from this account until the customer unlocks it.";

    private SupportLookupBinding binding;
    private SupportStaffTargets staffTargets;
    private SupportActionRepository actions;
    private SupportAccessLogWriter accessLog;
    private InnbucksAppSupportSection section;
    private DeviceSupportService deviceSupport;
    private CustomerDeviceRepository devices;
    private UserRepository users;
    private AccountSessionRevoker sessions;
    private AuditService audit;
    private SupportProperties props;
    private InnbucksAppSupportActions subject;
    private User customer;

    @BeforeEach
    void setUp() {
        binding = mock(SupportLookupBinding.class);
        staffTargets = mock(SupportStaffTargets.class);
        actions = mock(SupportActionRepository.class);
        accessLog = mock(SupportAccessLogWriter.class);
        section = mock(InnbucksAppSupportSection.class);
        deviceSupport = mock(DeviceSupportService.class);
        devices = mock(CustomerDeviceRepository.class);
        users = mock(UserRepository.class);
        sessions = mock(AccountSessionRevoker.class);
        audit = mock(AuditService.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        props = new SupportProperties();
        props.afterPropertiesSet();
        SupportProperties.Limiter generous = new SupportProperties.Limiter();
        generous.setShortWindowMax(1000);
        generous.setDailyMax(1000);
        SupportLookupLimiter limiter = new SupportLookupLimiter(null, generous, SupportMetrics.none(), () -> 0L);
        subject = new InnbucksAppSupportActions(limiter, binding, staffTargets, actions, accessLog,
                SupportMetrics.none(), section, deviceSupport, devices, users, sessions, audit, props, tm,
                Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC), "ZW");

        customer = User.builder().id(1042L).userUuid(UUID.randomUUID()).firstName("Tariro").lastName("Moyo")
                .phoneNumber(NUMBER).roles(new LinkedHashSet<>(List.of("CUSTOMER"))).active(true).approved(true)
                .build();
        when(devices.findByPublicId(DEVICE))
                .thenReturn(Optional.of(CustomerDevice.builder().publicId(DEVICE).msisdn(NUMBER).build()));
        when(devices.findByPublicId(OTHER_DEVICE))
                .thenReturn(Optional.of(CustomerDevice.builder().publicId(OTHER_DEVICE).msisdn("+263779999999")
                        .build()));
        when(actions.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of());
        when(deviceSupport.block(any(), any(), any(), any())).thenReturn(new ActionResult(null, NEXT));
        when(users.findByPhoneNumber(NUMBER)).thenReturn(Optional.of(customer));
        when(users.lockById(1042L)).thenReturn(Optional.of(customer));
        when(sessions.signOutEverywhere(any(), any())).thenReturn(new AccountSessionRevoker.Revocation(8L, 2, 0));
        bound(new SupportCustomerKeys(List.of(NUMBER), List.of(), List.of(), List.of(1042L), null));
    }

    private void bound(SupportCustomerKeys keys) {
        when(binding.bind(any(), eq("SLK-7Q2M9X"), eq(InnbucksAppSupportSection.NAME), any()))
                .thenReturn(new SupportLookupBinding.Bound("SLK-7Q2M9X", keys, List.of(InnbucksAppSupportSection.NAME),
                        Map.of(InnbucksAppSupportSection.NAME, List.of(NUMBER)), LocalDateTime.now(ZoneOffset.UTC)));
    }

    private static SupportAgent agent(String... authorities) {
        return new SupportAgent("agent.one@innbucks.co.zw", 7L, AGENT_UUID, "agent.one@innbucks.co.zw", null,
                "+263772000001", Set.of(authorities));
    }

    private static SupportAgent manager() {
        return agent(PermissionCatalog.DEVICE_SECURITY_READ, PermissionCatalog.DEVICE_SECURITY_MANAGE);
    }

    private static Write write() {
        return new Write("SLK-7Q2M9X", "Caller verified by date of birth; phone stolen.", null, "UNTIL_UNLOCKED",
                null, null, null);
    }

    private AppActionResult run(Op op, SupportAgent agent, Target target, String key) {
        return subject.execute(op, agent, target, write(), key, new AuditContext("41.221.147.12", "console"));
    }

    private static String code(Throwable e) {
        return ((SupportPolicyException) e).getErrorCode();
    }

    // ---- permission, note ------------------------------------------------------------------

    @Test
    @DisplayName("a ban re-checks device-security:fraud in the service; nothing else runs")
    void banNeedsTheFraudCode() {
        assertThatThrownBy(() -> run(Op.BAN, manager(), Target.device(DEVICE), KEY))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(binding, deviceSupport, actions, audit);
    }

    @Test
    @DisplayName("a note that is only markup is 400 note_required, before the lookup is read")
    void markupOnlyNote() {
        Write markup = new Write("SLK-7Q2M9X", "<b></b>", null, "UNTIL_UNLOCKED", null, null, null);
        assertThatThrownBy(() -> subject.execute(Op.BLOCK, manager(), Target.device(DEVICE), markup, KEY, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("note_required"));
        verifyNoInteractions(binding, deviceSupport);
    }

    @Test
    @DisplayName("support switched off is 404 support_disabled")
    void disabled() {
        props.setEnabled(false);
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_disabled"));
        verifyNoInteractions(binding, deviceSupport);
    }

    // ---- binding ---------------------------------------------------------------------------

    @Test
    @DisplayName("a phone on a number the search did not return is 404 target_not_found, and nothing is changed")
    void deviceOutsideTheLookup() {
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(OTHER_DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("target_not_found"));
        verifyNoInteractions(deviceSupport, actions, audit);
        ArgumentCaptor<SupportAccessLog> row = ArgumentCaptor.forClass(SupportAccessLog.class);
        verify(accessLog).bestEffort(row.capture());
        assertThat(row.getValue().getOutcome()).isEqualTo("target_not_found");
        assertThat(row.getValue().getOp()).isEqualTo("APP_DEVICE_BLOCK");
    }

    @Test
    @DisplayName("a device that does not exist is the same 404 target_not_found")
    void unknownDevice() {
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(UUID.randomUUID()), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("target_not_found"));
        verifyNoInteractions(deviceSupport);
    }

    @Test
    @DisplayName("a number outside the lookup is 404 target_not_found; one inside it matches in any spelling")
    void numberBinding() {
        assertThatThrownBy(() -> run(Op.CANCEL_CODES, manager(), Target.number("0779999999"), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("target_not_found"));
        verify(deviceSupport, never()).voidCodes(any(), any(), any(), any());

        when(deviceSupport.voidCodes(eq(NUMBER), any(), any(), any())).thenReturn(1);
        AppActionResult r = run(Op.CANCEL_CODES, manager(), Target.number("0771 234 567".replace(" ", "")), KEY);
        assertThat(r.whatHappensNext()).startsWith("1 open code was cancelled");
        verify(deviceSupport).voidCodes(eq(NUMBER), eq("Caller verified by date of birth; phone stolen."),
                eq("agent.one@innbucks.co.zw"), any());
    }

    @Test
    @DisplayName("a lookup refusal stops everything after it")
    void lookupRefusal() {
        when(binding.bind(any(), any(), any(), any())).thenThrow(SupportPolicyException.lookupExpired());
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("lookup_expired"));
        verifyNoInteractions(deviceSupport, actions, audit, staffTargets);
    }

    // ---- self, staff -----------------------------------------------------------------------

    @Test
    @DisplayName("the agent's own number is 403 support_self_action, audited as a refusal, nothing changed")
    void selfAction() {
        SupportAgent me = new SupportAgent("agent.one@innbucks.co.zw", 7L, AGENT_UUID, "agent.one@innbucks.co.zw",
                null, NUMBER, Set.of(PermissionCatalog.DEVICE_SECURITY_MANAGE));
        assertThatThrownBy(() -> run(Op.BLOCK, me, Target.device(DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("support_self_action"));
        verify(audit).recordFailure(eq(AuditEventType.SUPPORT_ACTION_REFUSED), eq("agent.one@innbucks.co.zw"),
                anyString(), eq("msisdn:****4567"), anyString(), eq("support_self_action"), any(), any());
        verifyNoInteractions(deviceSupport, actions);
    }

    @Test
    @DisplayName("a lookup that matched a staff account needs the supervisor code")
    void staffTarget() {
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("staff_target_requires_supervisor"));
        verifyNoInteractions(deviceSupport, actions);

        SupportAgent supervisor = agent(PermissionCatalog.DEVICE_SECURITY_MANAGE,
                PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE);
        assertThat(run(Op.BLOCK, supervisor, Target.device(DEVICE), KEY).outcome()).isEqualTo("SUCCESS");
    }

    // ---- idempotency -----------------------------------------------------------------------

    @Test
    @DisplayName("no Idempotency-Key is 400 idempotency_key_required, before anything changes")
    void keyRequired() {
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("idempotency_key_required"));
        verifyNoInteractions(deviceSupport, audit);
    }

    @Test
    @DisplayName("a repeated key returns the stored outcome with replayed: true and does nothing twice")
    void replay() {
        when(actions.findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(SupportAction.builder().op("APP_DEVICE_BLOCK").target(DEVICE.toString())
                        .outcome("SUCCESS").whatHappensNext(NEXT).build()));
        AppActionResult r = run(Op.BLOCK, manager(), Target.device(DEVICE), KEY);
        assertThat(r.replayed()).isTrue();
        assertThat(r.whatHappensNext()).isEqualTo(NEXT);
        verifyNoInteractions(deviceSupport, audit);
    }

    @Test
    @DisplayName("a key used for another action is 409 idempotency_key_reused")
    void keyReused() {
        when(actions.findByAgentUserUuidAndIdempotencyKey(AGENT_UUID, UUID.fromString(KEY)))
                .thenReturn(Optional.of(SupportAction.builder().op("APP_DEVICE_UNLOCK").target(DEVICE.toString())
                        .outcome("SUCCESS").whatHappensNext("Unlocked.").build()));
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("idempotency_key_reused"));
        verifyNoInteractions(deviceSupport);
    }

    // ---- act + seal ------------------------------------------------------------------------

    @Test
    @DisplayName("a block runs through DeviceSupportService under the agent's name, then the REQUIRED seal, last")
    void blockThenSeal() {
        AppActionResult r = run(Op.BLOCK, manager(), Target.device(DEVICE), KEY);

        assertThat(r.outcome()).isEqualTo("SUCCESS");
        assertThat(r.replayed()).isFalse();
        assertThat(r.whatHappensNext()).isEqualTo(NEXT);
        ArgumentCaptor<BlockRequest> req = ArgumentCaptor.forClass(BlockRequest.class);
        InOrder order = inOrder(actions, deviceSupport, audit);
        order.verify(actions).saveAndFlush(any());
        order.verify(deviceSupport).block(eq(DEVICE), req.capture(), eq("agent.one@innbucks.co.zw"), any());
        order.verify(actions).saveAndFlush(any());
        order.verify(audit).recordRequired(eq(AuditEventType.SUPPORT_INNBUCKS_APP_ACTION),
                eq("agent.one@innbucks.co.zw"), anyString(), eq("msisdn:****4567"), eq("CUSTOMER"), any(), any());
        assertThat(req.getValue().mode()).isEqualTo("UNTIL_UNLOCKED");
        verify(section).phone(NUMBER, customer);
    }

    @Test
    @DisplayName("the answer drops a staff account, as the search does: its profile never reaches the section")
    void theAnswerNeverCarriesAStaffAccount() {
        when(staffTargets.staffAccounts(any())).thenReturn(Set.of(customer.getUserUuid()));
        when(staffTargets.isStaffAccount(customer)).thenReturn(true);
        SupportAgent supervisor = agent(PermissionCatalog.DEVICE_SECURITY_MANAGE,
                PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE);
        run(Op.BLOCK, supervisor, Target.device(DEVICE), KEY);
        verify(section).phone(NUMBER, null);
    }

    @Test
    @DisplayName("a device-security refusal keeps its code, status and words, in the support envelope; no seal")
    void deviceRefusalKeepsItsCode() {
        when(deviceSupport.block(any(), any(), any(), any())).thenThrow(new DeviceSecurityException(
                HttpStatus.CONFLICT, "device_already_blocked", "This phone is already blocked (reference SEC-5T9W1H)."));
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .isInstanceOf(SupportPolicyException.class)
                .satisfies(e -> {
                    SupportPolicyException p = (SupportPolicyException) e;
                    assertThat(p.getErrorCode()).isEqualTo("device_already_blocked");
                    assertThat(p.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(p.getMessage()).isEqualTo("This phone is already blocked (reference SEC-5T9W1H).");
                });
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a write that cannot be sealed fails: the audit refusal propagates (the transaction rolls back)")
    void sealFailure() {
        doThrow(new AuditUnavailableException(new RuntimeException("db"))).when(audit)
                .recordRequired(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> run(Op.BLOCK, manager(), Target.device(DEVICE), KEY))
                .isInstanceOf(AuditUnavailableException.class);
    }

    @Test
    @DisplayName("unlock passes the fraud desk's authority only to an agent who holds it")
    void unlockFraudAuthority() {
        when(deviceSupport.unlock(any(), any(), any(), any(), eq(false))).thenReturn(new ActionResult(null, "Unlocked."));
        when(deviceSupport.unlock(any(), any(), any(), any(), eq(true))).thenReturn(new ActionResult(null, "Unlocked."));
        run(Op.UNLOCK, manager(), Target.device(DEVICE), KEY);
        verify(deviceSupport).unlock(eq(DEVICE), any(), any(), any(), eq(false));
        run(Op.UNLOCK, agent(PermissionCatalog.DEVICE_SECURITY_MANAGE, PermissionCatalog.DEVICE_SECURITY_FRAUD),
                Target.device(DEVICE), "5e4f7a8b-9c0d-4d8a-9c21-3f6c1a527b0e");
        verify(deviceSupport).unlock(eq(DEVICE), any(), any(), any(), eq(true));
    }

    // ---- sign out of the Super app ----------------------------------------------------------

    @Test
    @DisplayName("sign-out blocks the number's phones, then ends every session of its account, then seals")
    @SuppressWarnings("unchecked")
    void signOut() {
        when(deviceSupport.blockForSignOut(eq(NUMBER), any(), any(), any())).thenReturn(1);
        AppActionResult r = run(Op.SIGN_OUT, manager(), Target.number(NUMBER), KEY);

        InOrder order = inOrder(deviceSupport, users, sessions, audit);
        order.verify(deviceSupport).blockForSignOut(eq(NUMBER), eq("Caller verified by date of birth; phone stolen."),
                eq("agent.one@innbucks.co.zw"), any());
        order.verify(users).lockById(1042L);
        order.verify(sessions).signOutEverywhere(customer, "support_super_app_sign_out");
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        order.verify(audit).recordRequired(eq(AuditEventType.SUPPORT_SUPER_APP_SIGNED_OUT),
                eq("agent.one@innbucks.co.zw"), anyString(), eq("msisdn:****4567"), eq("CUSTOMER"), meta.capture(),
                any());
        assertThat(meta.getValue()).containsEntry("phonesBlocked", 1).containsEntry("refreshTokensRevoked", 2)
                .containsEntry("account", true).containsEntry("op", "APP_SIGN_OUT");
        assertThat(r.whatHappensNext()).startsWith("Every session of the customer's InnBucks account has ended")
                .contains("1 phone is blocked").contains("Rewards has its own sign-out");
    }

    @Test
    @DisplayName("sign-out on a number with no account still blocks its phones and says there was no session")
    void signOutNoAccount() {
        when(users.findByPhoneNumber(NUMBER)).thenReturn(Optional.empty());
        AppActionResult r = run(Op.SIGN_OUT, manager(), Target.number(NUMBER), KEY);
        verify(deviceSupport).blockForSignOut(eq(NUMBER), any(), any(), any());
        verify(sessions, never()).signOutEverywhere(any(), any());
        assertThat(r.whatHappensNext()).startsWith(InnbucksAppSupportActions.NO_ACCOUNT_SENTENCE);
    }

    @Test
    @DisplayName("sign-out never ends a staff account's sessions: 403 console_staff_account, nothing sealed")
    void signOutRefusesStaff() {
        when(staffTargets.isStaffAccount(customer)).thenReturn(true);
        assertThatThrownBy(() -> run(Op.SIGN_OUT, manager(), Target.number(NUMBER), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
        verify(sessions, never()).signOutEverywhere(any(), any());
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sign-out never ends a SUPER_ADMIN's sessions")
    void signOutRefusesSuperAdmin() {
        customer.setRoles(new LinkedHashSet<>(List.of("SUPER_ADMIN")));
        assertThatThrownBy(() -> run(Op.SIGN_OUT, manager(), Target.number(NUMBER), KEY))
                .satisfies(e -> assertThat(code(e)).isEqualTo("console_staff_account"));
        verify(sessions, never()).signOutEverywhere(any(), any());
    }
}
