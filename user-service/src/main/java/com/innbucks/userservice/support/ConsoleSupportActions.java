package com.innbucks.userservice.support;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.SupportMfaResetNotice;
import com.innbucks.userservice.exception.StaffPolicyException;
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
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.support.dto.SupportDTOs.ActionResult;
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountView;
import com.innbucks.userservice.support.dto.SupportDTOs.WriteRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The Foundry console section's support writes and detail read
 * ({@code /admin/support/console-users/{id}/…}, design §3.1.5 and §3.2).
 *
 * <p>Every write runs the same steps in the same order — the order is part of
 * the security, so do not reshuffle it:
 * <ol>
 *   <li><b>Permission</b>: {@code @PreAuthorize}, re-checked here (a second tier,
 *       {@code support-console:mfa:reset}, is a supervisor code). The call also
 *       counts against the agent's lookup limit.</li>
 *   <li><b>Lookup binding</b> ({@link SupportLookupBinding}): this agent's own
 *       lookup, fresh, that returned the console section, with the target among
 *       its accounts.</li>
 *   <li><b>Self-action</b>: 403 {@code support_self_action} when the lookup's keys
 *       are the agent's own.</li>
 *   <li><b>Staff targets</b>: when the keys reach an InnBucks staff account (a
 *       FRESH read, not the flag stored with the lookup) the agent needs
 *       {@code support-staff-targets:manage} — 403
 *       {@code staff_target_requires_supervisor}. The console guard then refuses
 *       a staff or SUPER_ADMIN target outright (403 {@code console_staff_account}):
 *       staff are managed through {@code /admin/staff}, not by support.</li>
 *   <li><b>Idempotency</b>: a required {@code Idempotency-Key} (UUID); a repeat of
 *       the key returns the stored outcome ({@code replayed: true}) and acts
 *       nothing twice. A concurrent repeat waits on the unique index and is then
 *       answered from the first request's row.</li>
 *   <li><b>Act</b>, in one transaction with the account row locked.</li>
 *   <li><b>Seal</b>: {@code SUPPORT_CONSOLE_*} via {@link AuditService#recordRequired},
 *       the transaction's LAST statement — an in-process write that cannot be
 *       recorded is not made (503 {@code audit_unavailable}).</li>
 *   <li><b>Tell</b> the agent what happens next, in a server-rendered sentence.</li>
 * </ol>
 */
@Service
@Slf4j
public class ConsoleSupportActions {

    /** The three console writes: operation name, permission, and the event they seal. */
    public enum Op {
        UNLOCK("CONSOLE_UNLOCK", PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                AuditEventType.SUPPORT_CONSOLE_ACCOUNT_UNLOCKED),
        SEND_PASSWORD_RESET("CONSOLE_SEND_PASSWORD_RESET", PermissionCatalog.SUPPORT_CONSOLE_MANAGE,
                AuditEventType.SUPPORT_CONSOLE_PASSWORD_RESET_SENT),
        MFA_RESET("CONSOLE_MFA_RESET", PermissionCatalog.SUPPORT_CONSOLE_MFA_RESET,
                AuditEventType.SUPPORT_CONSOLE_MFA_RESET);

        private final String operation;
        private final String permission;
        private final AuditEventType event;

        Op(String operation, String permission, AuditEventType event) {
            this.operation = operation;
            this.permission = permission;
            this.event = event;
        }

        public String operation() {
            return operation;
        }

        public String permission() {
            return permission;
        }

        public AuditEventType event() {
            return event;
        }
    }

    static final String OP_DETAIL = "CONSOLE_DETAIL";

    static final String UNLOCKED_NEXT = "The account can sign in again now. If the caller is still refused, ask them "
            + "to wait a minute and try once more.";
    static final String NOT_LOCKED_NEXT = "This account wasn't locked, so nothing changed. If the caller still can't "
            + "sign in, check the email they use or send a reset code.";
    static final String MFA_RESET_NEXT = "Two-factor sign-in is off for this account and every session it had open "
            + "has ended. At their next sign-in they'll be asked to set it up again. We've emailed the account and "
            + "the owners of its businesses about this change.";

    static String resetSentNext(String email) {
        return "We've emailed a password-reset code to " + email + ". It works for "
                + OtpService.OTP_TTL.toMinutes() + " minutes: ask the caller to choose Forgot password on the "
                + "Foundry sign-in page, enter this email, then the code.";
    }

    private final SupportLookupLimiter limiter;
    private final SupportLookupBinding binding;
    private final SupportStaffTargets staffTargets;
    private final SupportActionRepository actions;
    private final SupportAccessLogWriter accessLog;
    private final SupportMetrics metrics;
    private final ConsoleSupportSection console;
    private final UserRepository users;
    private final OrganizationMemberRepository members;
    private final OrganizationRepository organizations;
    private final MfaService mfaService;
    private final PasswordResetService passwordResetService;
    private final RoleGrantGuard roleGrantGuard;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final SupportProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConsoleSupportActions(SupportLookupLimiter limiter, SupportLookupBinding binding,
                                 SupportStaffTargets staffTargets, SupportActionRepository actions,
                                 SupportAccessLogWriter accessLog, SupportMetrics metrics, ConsoleSupportSection console,
                                 UserRepository users, OrganizationMemberRepository members,
                                 OrganizationRepository organizations, MfaService mfaService,
                                 PasswordResetService passwordResetService, RoleGrantGuard roleGrantGuard,
                                 AuditService auditService, ApplicationEventPublisher events,
                                 SupportProperties properties, PlatformTransactionManager transactionManager,
                                 @Qualifier("supportClock") Clock supportClock) {
        this.limiter = limiter;
        this.binding = binding;
        this.staffTargets = staffTargets;
        this.actions = actions;
        this.accessLog = accessLog;
        this.metrics = metrics;
        this.console = console;
        this.users = users;
        this.members = members;
        this.organizations = organizations;
        this.mfaService = mfaService;
        this.passwordResetService = passwordResetService;
        this.roleGrantGuard = roleGrantGuard;
        this.auditService = auditService;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = supportClock;
    }

    // ---- detail read -------------------------------------------------------------------------

    /**
     * {@code GET /admin/support/console-users/{id}?lookupId=} — the account as it
     * is now, bound to the lookup like a write (so an id from outside the lookup
     * is simply not found), counted and logged fail-closed like a search.
     */
    public ConsoleAccountView detail(SupportAgent agent, Long userId, String lookupId, String clientIp) {
        SupportSearchService.requireEnabled(properties);
        limiter.acquire(agent);
        SupportLookupBinding.Bound bound;
        try {
            bound = binding.bind(agent, lookupId, ConsoleSupportSection.NAME, String.valueOf(userId));
        } catch (SupportPolicyException e) {
            refusalLog(agent, OP_DETAIL, lookupId, userId, null, e.getErrorCode(), clientIp);
            throw e;
        }
        User user = users.findById(userId).orElseThrow(SupportPolicyException::targetNotFound);
        ConsoleAccountView view = console.view(user, agent, LocalDateTime.now(clock));
        accessLog.required(row(agent, OP_DETAIL, bound.lookupId(), userId, bound.keys(), "OK", clientIp));
        metrics.lookup("ok");
        return view;
    }

    // ---- writes ------------------------------------------------------------------------------

    public ActionResult execute(Op op, SupportAgent agent, Long userId, WriteRequest body, String idempotencyKey,
                                AuditContext ctx) {
        SupportSearchService.requireEnabled(properties);
        String clientIp = ctx == null ? null : ctx.ipAddress();
        // 1. Permission — @PreAuthorize ran; the higher tier is re-checked here.
        if (!agent.holds(op.permission())) {
            throw new AccessDeniedException("missing " + op.permission());
        }
        limiter.acquire(agent);
        String lookupId = body == null ? null : body.lookupId();
        SupportLookupBinding.Bound bindingResult = null;
        try {
            // 2. Lookup binding.
            bindingResult = binding.bind(agent, lookupId, ConsoleSupportSection.NAME, String.valueOf(userId));
            SupportLookupBinding.Bound bound = bindingResult;
            // 3. Self-action.
            if (agent.isSelf(bound.keys()) || Objects.equals(agent.userId(), userId)) {
                throw refuseAimed(agent, op, bound, userId, SupportPolicyException.selfAction(), ctx);
            }
            // 4. Staff targets — a fresh read, never the lookup's stored flag.
            Set<UUID> staff = staffTargets.staffAccounts(bound.keys());
            if (!staff.isEmpty() && !agent.holds(PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE)) {
                throw refuseAimed(agent, op, bound, userId, SupportPolicyException.staffTargetRequiresSupervisor(),
                        ctx);
            }
        } catch (SupportPolicyException e) {
            refusalLog(agent, op.operation(), lookupId, userId, bindingResult == null ? null : bindingResult.keys(),
                    e.getErrorCode(), clientIp);
            throw e;
        }
        final SupportLookupBinding.Bound bound = bindingResult;

        // 5. Idempotency.
        UUID key = parseKey(idempotencyKey);
        if (key == null) {
            refusalLog(agent, op.operation(), bound.lookupId(), userId, bound.keys(),
                    SupportPolicyException.IDEMPOTENCY_KEY_REQUIRED, clientIp);
            throw SupportPolicyException.idempotencyKeyRequired();
        }
        Optional<SupportAction> earlier = actions.findByAgentUserUuidAndIdempotencyKey(agent.userUuid(), key);
        if (earlier.isPresent()) {
            return replay(earlier.get(), op, agent, userId, bound, clientIp);
        }

        // 6 + 7. Act and seal, in one transaction.
        String next;
        try {
            next = tx.execute(status -> act(op, agent, userId, body, key, bound, ctx));
        } catch (DataIntegrityViolationException race) {
            // The same key, concurrently: the first request's row decides.
            SupportAction first = actions.findByAgentUserUuidAndIdempotencyKey(agent.userUuid(), key)
                    .orElseThrow(() -> race);
            return replay(first, op, agent, userId, bound, clientIp);
        } catch (SupportPolicyException e) {
            refusalLog(agent, op.operation(), bound.lookupId(), userId, bound.keys(), e.getErrorCode(), clientIp);
            throw e;
        }
        accessLog.bestEffort(row(agent, op.operation(), bound.lookupId(), userId, bound.keys(), "OK", clientIp));
        metrics.lookup("ok");
        // 8. What happens next, with the account as it is now.
        return new ActionResult(SupportAction.OUTCOME_SUCCESS, next, false, currentView(userId, agent));
    }

    /** Runs inside the transaction. Returns the whatHappensNext sentence. */
    private String act(Op op, SupportAgent agent, Long userId, WriteRequest body, UUID key,
                       SupportLookupBinding.Bound bound, AuditContext ctx) {
        User user = users.lockById(userId).orElseThrow(SupportPolicyException::targetNotFound);
        if (user.hasRole(User.Role.SUPER_ADMIN) || staffTargets.isStaffAccount(user)) {
            throw refuseAimed(agent, op, bound, userId, SupportPolicyException.consoleStaffAccount(), ctx);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String customerKey = SupportMasking.customerKey(user.getPhoneNumber(), user.getEmail());
        // The idempotency row goes in FIRST: its unique index is what makes a
        // concurrent repeat wait here instead of acting a second time.
        SupportAction row = actions.saveAndFlush(SupportAction.builder()
                .agentUserUuid(agent.userUuid())
                .idempotencyKey(key)
                .lookupId(bound.lookupId())
                .section(ConsoleSupportSection.NAME)
                .op(op.operation())
                .target(String.valueOf(userId))
                .customerKey(customerKey)
                .outcome(SupportAction.OUTCOME_PENDING)
                .caseId(body.caseId())
                .createdAt(now)
                .build());

        Map<String, Object> metadata = new LinkedHashMap<>();
        String next = switch (op) {
            case UNLOCK -> unlock(user, metadata);
            case SEND_PASSWORD_RESET -> sendPasswordReset(user);
            case MFA_RESET -> resetMfa(user, agent, metadata, now);
        };

        row.setOutcome(SupportAction.OUTCOME_SUCCESS);
        row.setWhatHappensNext(next);
        row.setCompletedAt(LocalDateTime.now(clock));
        actions.saveAndFlush(row);

        metadata.put("customerKey", customerKey);
        metadata.put("lookupId", bound.lookupId());
        metadata.put("idempotencyKey", key.toString());
        metadata.put("section", ConsoleSupportSection.NAME);
        metadata.put("outcome", SupportAction.OUTCOME_SUCCESS);
        String note = MfaService.cleanNote(body.note());
        if (note != null) metadata.put("note", note);
        // 7. The seal — LAST, fail-closed.
        auditService.recordRequired(op.event(), agent.subject(), AuditService.ACTOR_TYPE_USER,
                String.valueOf(userId), AuditService.TARGET_TYPE_USER, metadata,
                ctx == null ? AuditContext.none() : ctx);
        log.info("Support {} done userId={} agent={} lookupId={}", op.operation(), userId, agent.userUuid(),
                bound.lookupId());
        return next;
    }

    private String unlock(User user, Map<String, Object> metadata) {
        boolean wasLocked = user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null
                || user.getMfaFailedAttempts() > 0 || user.getMfaLockedUntil() != null;
        metadata.put("wasLocked", wasLocked);
        if (!wasLocked) return NOT_LOCKED_NEXT;
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        users.saveAndFlush(user);
        return UNLOCKED_NEXT;
    }

    private String sendPasswordReset(User user) {
        if (!user.isActive() || !user.isApproved()) throw SupportPolicyException.accountInactive();
        String email = user.getEmail();
        if (email == null || email.isBlank()) throw SupportPolicyException.noEmailOnAccount();
        try {
            // ONLY the account's email: PasswordResetService prefers the email when
            // both are given, but passing the phone at all is how a reset would be
            // aimed at a number support never verified.
            passwordResetService.requestReset(null, email);
        } catch (OtpService.OtpRateLimitException e) {
            throw SupportPolicyException.resetCodeLimited();
        } catch (NotificationDeliveryException e) {
            throw SupportPolicyException.resetDeliveryFailed();
        }
        return resetSentNext(email);
    }

    private String resetMfa(User user, SupportAgent agent, Map<String, Object> metadata, LocalDateTime now) {
        if (!user.isMfaEnabled() && user.getMfaSecret() == null) throw SupportPolicyException.mfaNotEnrolled();
        // Caller ⊇ target (PR 1b's rule for an MFA reset), where it applies: a
        // console target is never a staff account, so it holds no PLATFORM code
        // and the comparison is over PLATFORM codes only — a supervisor does not
        // hold a merchant's TENANT codes and was never meant to.
        Set<String> platformHeld = new TreeSet<>();
        for (String code : roleGrantGuard.storedGrants(user)) {
            if (PermissionCatalog.WILDCARD.equals(code)
                    || PermissionCatalog.scopeOf(code) == PermissionCatalog.Scope.PLATFORM) {
                platformHeld.add(code);
            }
        }
        if (!platformHeld.isEmpty() && !roleGrantGuard.resolveCaller(agent.subject()).holdsAll(platformHeld)) {
            throw StaffPolicyException.targetNotManageable(StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
        }
        long version = mfaService.resetForSupport(user);
        metadata.put("tokenVersion", version);

        List<OrganizationMember> memberships = members.findByUserId(user.getId());
        List<UUID> orgIds = memberships.stream().map(OrganizationMember::getOrganizationId).toList();
        Set<String> ownerEmails = new LinkedHashSet<>();
        List<String> orgNames = List.of();
        if (!orgIds.isEmpty()) {
            orgNames = organizations.findAllById(orgIds).stream().map(Organization::getName)
                    .filter(Objects::nonNull).sorted().toList();
            List<Long> ownerIds = members.findByOrganizationIdInAndRole(orgIds, OrganizationMember.Role.OWNER).stream()
                    .map(OrganizationMember::getUserId).filter(id -> !id.equals(user.getId())).distinct().toList();
            String own = user.getEmail() == null ? null : user.getEmail().toLowerCase(Locale.ROOT);
            for (User owner : users.findAllById(ownerIds)) {
                if (owner.getEmail() == null || owner.getEmail().isBlank()) continue;
                String e = owner.getEmail().toLowerCase(Locale.ROOT);
                if (!e.equals(own)) ownerEmails.add(e);
            }
        }
        metadata.put("ownersNotified", ownerEmails.size());
        events.publishEvent(new SupportMfaResetNotice(user.getId(), ConsoleSupportSection.name(user), user.getEmail(),
                orgNames, List.copyOf(ownerEmails), now));
        return MFA_RESET_NEXT;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ActionResult replay(SupportAction earlier, Op op, SupportAgent agent, Long userId,
                                SupportLookupBinding.Bound bound, String clientIp) {
        if (!earlier.getOp().equals(op.operation()) || !earlier.getTarget().equals(String.valueOf(userId))) {
            refusalLog(agent, op.operation(), bound.lookupId(), userId, bound.keys(),
                    SupportPolicyException.IDEMPOTENCY_KEY_REUSED, clientIp);
            throw SupportPolicyException.idempotencyKeyReused();
        }
        accessLog.bestEffort(row(agent, op.operation(), bound.lookupId(), userId, bound.keys(), "REPLAYED", clientIp));
        metrics.lookup("replayed");
        return new ActionResult(earlier.getOutcome(), earlier.getWhatHappensNext(), true, currentView(userId, agent));
    }

    private ConsoleAccountView currentView(Long userId, SupportAgent agent) {
        return users.findById(userId).map(u -> console.view(u, agent, LocalDateTime.now(clock))).orElse(null);
    }

    /**
     * A refusal for WHO the write was aimed at — the agent themselves, or staff:
     * a FAILURE row on the audit chain (fail-open, like every refusal row), since
     * these are what an insider-threat review looks for.
     */
    private SupportPolicyException refuseAimed(SupportAgent agent, Op op, SupportLookupBinding.Bound bound,
                                               Long userId, SupportPolicyException refusal, AuditContext ctx) {
        log.warn("Support {} refused ({}) userId={} agent={} lookupId={}", op.operation(), refusal.getErrorCode(),
                userId, agent.userUuid(), bound.lookupId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("op", op.operation());
        metadata.put("lookupId", bound.lookupId());
        auditService.recordFailure(AuditEventType.SUPPORT_ACTION_REFUSED, agent.subject(),
                AuditService.ACTOR_TYPE_USER, String.valueOf(userId), AuditService.TARGET_TYPE_USER,
                refusal.getErrorCode(), metadata, ctx == null ? AuditContext.none() : ctx);
        return refusal;
    }

    private void refusalLog(SupportAgent agent, String op, String lookupId, Long userId, SupportCustomerKeys keys,
                            String code, String clientIp) {
        metrics.lookup(code);
        if (SupportPolicyException.LOOKUP_RATE_LIMITED.equals(code)) return;
        String id = lookupId == null ? null : SupportSearchService.truncate(lookupId.strip(), 16);
        accessLog.bestEffort(row(agent, op, id, userId, keys, code, clientIp));
    }

    private SupportAccessLog row(SupportAgent agent, String op, String lookupId, Long userId,
                                 SupportCustomerKeys keys, String outcome, String clientIp) {
        return SupportAccessLog.builder()
                .createdAt(LocalDateTime.now(clock))
                .lookupId(lookupId)
                .op(op)
                .outcome(outcome)
                .agentUserUuid(agent.userUuid())
                .agentSubject(agent.subject())
                .customerKeys(keys == null ? null : SupportAccessLogWriter.json(keys))
                .sections(ConsoleSupportSection.NAME)
                .target(userId == null ? null : String.valueOf(userId))
                .clientIpUntrusted(SupportSearchService.truncate(clientIp, 64))
                .build();
    }

    static UUID parseKey(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            UUID key = UUID.fromString(header.strip());
            return key.toString().equalsIgnoreCase(header.strip()) ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
