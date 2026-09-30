package com.innbucks.userservice.support;

import com.innbucks.userservice.entity.Organization;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.SupportMfaResetNotice;
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
import com.innbucks.userservice.support.dto.SupportDTOs.ConsoleAccountEntry;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li><b>Act</b>, in one transaction with the account row locked — on an
 *       account that is, on that fresh read, neither staff (403
 *       {@code console_staff_account}) nor deactivated or pending approval (409
 *       {@code account_inactive}).</li>
 *   <li><b>Seal</b>: {@code SUPPORT_CONSOLE_*} via {@link AuditService#recordRequired},
 *       the transaction's LAST statement — an in-process write that cannot be
 *       recorded is not made (503 {@code audit_unavailable}). Every message the
 *       action causes (the reset email, the owners' notice, the account's alert)
 *       leaves only AFTER this commit, so a refused seal sends nothing.</li>
 *   <li><b>Tell</b> the agent what happens next, in a server-rendered sentence.</li>
 * </ol>
 *
 * <p>Before any of it, the note: sealed cleaned ({@link MfaService#cleanNote}),
 * and one with nothing left once cleaned is 400 {@code note_required}.
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
    private static final String MFA_RESET_DONE = "Two-factor sign-in is off for this account and every session it "
            + "had open has ended. At their next sign-in they'll be asked to set it up again. ";
    /** After an MFA reset that emailed at least one other owner of the account's businesses. */
    static final String MFA_RESET_NEXT = MFA_RESET_DONE + "We've sent the account a security alert and emailed the "
            + "other owners of its businesses.";
    /** After an MFA reset with no other owner on file to tell. */
    static final String MFA_RESET_NEXT_NO_OWNERS = MFA_RESET_DONE + "We've sent the account a security alert. No "
            + "business owner was emailed: its businesses have no other owner with an email on file.";

    /**
     * The code goes out AFTER the action commits and is sealed, off the request
     * thread — so this sentence says the email is on its way, never that it
     * arrived: the response is written before delivery is known.
     */
    static String resetSentNext(String email) {
        return "We're emailing a password-reset code to " + email + " now. It works for "
                + OtpService.OTP_TTL.toMinutes() + " minutes: ask the caller to choose Forgot password on the "
                + "Foundry sign-in page, enter this email, then the code. If it hasn't arrived within a couple of "
                + "minutes, ask them to check their spam folder before you send another.";
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
                                 PasswordResetService passwordResetService,
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
     * is simply not found), counted and logged fail-closed like a search. An
     * account that has become a staff account since the search is refused (403
     * {@code console_staff_account}) on a FRESH read — never shown. Every refusal
     * is access-logged, best-effort.
     */
    public ConsoleAccountView detail(SupportAgent agent, Long userId, String lookupId, String clientIp) {
        SupportSearchService.requireEnabled(properties);
        limiter.acquire(agent);
        SupportLookupBinding.Bound bound = null;
        User user;
        try {
            bound = binding.bind(agent, lookupId, ConsoleSupportSection.NAME, String.valueOf(userId));
            user = users.findById(userId).orElseThrow(SupportPolicyException::targetNotFound);
            if (user.hasRole(User.Role.SUPER_ADMIN) || staffTargets.isStaffAccount(user)) {
                log.warn("Support detail read refused: the account is now a STAFF account userId={} agent={} "
                        + "lookupId={}", userId, agent.userUuid(), bound.lookupId());
                throw SupportPolicyException.consoleStaffAccount();
            }
        } catch (SupportPolicyException e) {
            refusalLog(agent, OP_DETAIL, lookupId, userId, bound == null ? null : bound.keys(), e.getErrorCode(),
                    clientIp);
            throw e;
        }
        ConsoleAccountView view = console.view(user, agent, LocalDateTime.now(clock));
        // Fail-CLOSED: a read whose row cannot be written is not shown.
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
        // The note is the seal's "why". @NotBlank let it through, but it is sealed
        // CLEANED (markup and invisible characters stripped); one that is empty
        // once cleaned is refused like a missing one — never sealed as nothing.
        // Before the limiter, like the bean validation it completes.
        String note = body == null ? null : MfaService.cleanNote(body.note());
        if (note == null) {
            throw SupportPolicyException.noteRequired();
        }
        limiter.acquire(agent);
        String lookupId = body.lookupId();
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

        // 6 + 7. Act and seal, in one transaction. Anything that must happen
        // only if the action stands — the owners' notice, the account's alert,
        // the reset email — runs AFTER this commit, so a refused seal rolls it
        // back unsent.
        String next;
        try {
            next = tx.execute(status -> act(op, agent, userId, note, body.caseId(), key, bound, ctx));
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
    private String act(Op op, SupportAgent agent, Long userId, String note, String caseId, UUID key,
                       SupportLookupBinding.Bound bound, AuditContext ctx) {
        User user = users.lockById(userId).orElseThrow(SupportPolicyException::targetNotFound);
        // The account ITSELF, read fresh under its row lock: staff and SUPER_ADMIN
        // are never console targets, even for a supervisor — and an account that
        // became staff after the search is refused here, whatever the lookup says.
        if (user.hasRole(User.Role.SUPER_ADMIN) || staffTargets.isStaffAccount(user)) {
            throw refuseAimed(agent, op, bound, userId, SupportPolicyException.consoleStaffAccount(), ctx);
        }
        // Every console write needs an account that is active AND approved: a
        // deactivated one is an administrator's decision support must not work
        // around (an unlock or a fresh second factor waiting for a reactivation),
        // and a pending one has not been let in yet.
        if (!user.isActive() || !user.isApproved()) {
            throw SupportPolicyException.accountInactive();
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
                .caseId(caseId)
                .createdAt(now)
                .build());

        Map<String, Object> metadata = new LinkedHashMap<>();
        String next = switch (op) {
            case UNLOCK -> unlock(user, metadata);
            case SEND_PASSWORD_RESET -> sendPasswordReset(user, metadata);
            case MFA_RESET -> resetMfa(user, metadata, now);
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
        metadata.put("note", note);
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

    /**
     * Issues the reset code inside this transaction (the OTP row and the quota
     * commit with the seal, or not at all) and sends it only AFTER commit, off the
     * request thread: the users row lock is never held across the email gateway,
     * and a seal that fails rolls the code back unsent. So the agent is told the
     * email is on its way — delivery is known only later, as the
     * {@code user.support.reset_delivery{outcome}} meter and a log line.
     */
    private String sendPasswordReset(User user, Map<String, Object> metadata) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) throw SupportPolicyException.noEmailOnAccount();
        boolean issued;
        try {
            // ONLY the account's email: passing the phone at all is how a reset
            // would be aimed at a number support never verified.
            issued = passwordResetService.requestResetForSupport(email,
                    delivered -> metrics.resetDelivery(delivered ? "sent" : "failed"));
        } catch (OtpService.OtpRateLimitException e) {
            throw SupportPolicyException.resetCodeLimited();
        }
        if (!issued) {
            // PasswordResetService refused to issue (deactivated, or an invite
            // pending) — execute() checked the first, and staff never get here.
            throw SupportPolicyException.accountInactive();
        }
        metadata.put("delivery", "after_commit");
        return resetSentNext(email);
    }

    /**
     * The wipe, through {@link MfaService#resetForSupport}. No caller-covers-target
     * comparison here, deliberately: {@link #act} has already refused a staff
     * account on a fresh read, and an account that is not staff holds no PLATFORM
     * code (a role granting one IS a staff role), so there is nothing for a
     * supervisor to fall short of. A merchant's TENANT codes were never compared.
     */
    private String resetMfa(User user, Map<String, Object> metadata, LocalDateTime now) {
        if (!ConsoleSupportSection.hasSecondFactor(user)) throw SupportPolicyException.mfaNotEnrolled();
        long version = mfaService.resetForSupport(user);
        metadata.put("tokenVersion", version);

        List<SupportMfaResetNotice.OwnerNotice> owners = ownersToTell(user);
        metadata.put("ownersNotified", owners.size());
        if (!owners.isEmpty()) {
            events.publishEvent(new SupportMfaResetNotice(user.getId(), ConsoleSupportSection.name(user),
                    user.getEmail(), owners, now));
        }
        return owners.isEmpty() ? MFA_RESET_NEXT_NO_OWNERS : MFA_RESET_NEXT;
    }

    /**
     * Every OTHER owner of the account's businesses, each with ONLY the
     * businesses they own: an owner of one business must not learn which other
     * businesses the account belongs to, nor be told they own them. One notice
     * per address (lower-cased), never the account's own.
     */
    List<SupportMfaResetNotice.OwnerNotice> ownersToTell(User user) {
        List<UUID> orgIds = members.findByUserId(user.getId()).stream()
                .map(OrganizationMember::getOrganizationId).distinct().toList();
        if (orgIds.isEmpty()) return List.of();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Organization o : organizations.findAllById(orgIds)) names.put(o.getId(), o.getName());
        Map<Long, Set<UUID>> ownedBy = new LinkedHashMap<>();
        for (OrganizationMember m : members.findByOrganizationIdInAndRole(orgIds, OrganizationMember.Role.OWNER)) {
            if (m.getUserId() == null || m.getUserId().equals(user.getId())) continue;
            ownedBy.computeIfAbsent(m.getUserId(), id -> new LinkedHashSet<>()).add(m.getOrganizationId());
        }
        if (ownedBy.isEmpty()) return List.of();
        String own = user.getEmail() == null ? null : user.getEmail().toLowerCase(Locale.ROOT);
        Map<String, Set<String>> byEmail = new TreeMap<>();
        for (User owner : users.findAllById(new ArrayList<>(ownedBy.keySet()))) {
            if (owner.getEmail() == null || owner.getEmail().isBlank()) continue;
            String e = owner.getEmail().toLowerCase(Locale.ROOT);
            if (e.equals(own)) continue;
            Set<String> theirs = byEmail.computeIfAbsent(e, k -> new TreeSet<>());
            for (UUID org : ownedBy.getOrDefault(owner.getId(), Set.of())) {
                String name = names.get(org);
                if (name != null && !name.isBlank()) theirs.add(name);
            }
        }
        List<SupportMfaResetNotice.OwnerNotice> out = new ArrayList<>();
        byEmail.forEach((e, orgs) -> out.add(new SupportMfaResetNotice.OwnerNotice(e, List.copyOf(orgs))));
        return List.copyOf(out);
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

    /** A staff stub if the account has become staff since (a replay can land after that); null if it is gone. */
    private ConsoleAccountEntry currentView(Long userId, SupportAgent agent) {
        return users.findById(userId).map(u -> console.entry(u, agent, LocalDateTime.now(clock))).orElse(null);
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
                .agentSubject(SupportAccessLogWriter.subject(agent.subject()))
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
