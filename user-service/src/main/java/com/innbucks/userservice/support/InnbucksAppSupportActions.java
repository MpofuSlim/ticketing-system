package com.innbucks.userservice.support;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import com.innbucks.userservice.devicesecurity.DeviceSupportService;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BanRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.BlockRequest;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.FraudFlagRequest;
import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import com.innbucks.userservice.devicesecurity.repository.CustomerDeviceRepository;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.SupportPolicyException;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.service.AccountSessionRevoker;
import com.innbucks.userservice.service.AuditContext;
import com.innbucks.userservice.service.AuditEventType;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.support.dto.SupportDTOs.AppActionResult;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The InnBucks app (DTX) section's support writes
 * ({@code /admin/support/innbucks-app/…}): the seven device-security actions —
 * block, ban, unlock, remove, reset trust, fraud flag, cancel codes — and "sign
 * out of the Super app", bound to a lookup like the console writes.
 *
 * <p>Before this, those seven ran only on {@code /admin/device-security/**},
 * with no lookup binding, no per-agent limit and no idempotency: the strongest
 * support actions there are were the least guarded. Here every one runs the
 * same steps, in the same order, as {@link ConsoleSupportActions}:
 * <ol>
 *   <li><b>Permission</b>: {@code @PreAuthorize}, re-checked here; counted
 *       against the agent's lookup limit.</li>
 *   <li><b>Lookup binding</b>: this agent's own fresh lookup that returned the
 *       {@code innbucksApp} section, with the target NUMBER among its phones.
 *       A device is resolved to its number first; a device that does not exist
 *       and one on a number outside the lookup are the same {@code 404
 *       target_not_found}.</li>
 *   <li><b>Self-action</b> and <b>staff targets</b>, as for the console.</li>
 *   <li><b>Idempotency</b>: a required {@code Idempotency-Key}; a repeat returns
 *       the stored outcome ({@code replayed: true}).</li>
 *   <li><b>Act</b> through {@link DeviceSupportService} (its own guards and
 *       notices unchanged; the customer's SMS goes after commit), and <b>seal</b>
 *       with a REQUIRED audit row as the LAST statement — a write that cannot be
 *       recorded is not made.</li>
 *   <li><b>Tell</b> the agent what happens next, with the number as it is now.</li>
 * </ol>
 */
@Service
@Slf4j
public class InnbucksAppSupportActions {

    /** What each write is, the permission it needs, and whether it targets a phone (device) or a number. */
    public enum Op {
        BLOCK("APP_DEVICE_BLOCK", PermissionCatalog.DEVICE_SECURITY_MANAGE, true),
        BAN("APP_DEVICE_BAN", PermissionCatalog.DEVICE_SECURITY_FRAUD, true),
        UNLOCK("APP_DEVICE_UNLOCK", PermissionCatalog.DEVICE_SECURITY_MANAGE, true),
        REVOKE("APP_DEVICE_REVOKE", PermissionCatalog.DEVICE_SECURITY_MANAGE, true),
        RESET_TRUST("APP_DEVICE_RESET_TRUST", PermissionCatalog.DEVICE_SECURITY_MANAGE, true),
        FRAUD_FLAG("APP_FRAUD_FLAG", PermissionCatalog.DEVICE_SECURITY_FRAUD, false),
        CANCEL_CODES("APP_CANCEL_CODES", PermissionCatalog.DEVICE_SECURITY_MANAGE, false),
        SIGN_OUT("APP_SIGN_OUT", PermissionCatalog.DEVICE_SECURITY_MANAGE, false);

        private final String operation;
        private final String permission;
        private final boolean deviceTarget;

        Op(String operation, String permission, boolean deviceTarget) {
            this.operation = operation;
            this.permission = permission;
            this.deviceTarget = deviceTarget;
        }

        public String operation() {
            return operation;
        }

        public String permission() {
            return permission;
        }
    }

    /**
     * What a write is aimed at: one phone (by its opaque device id) or one number
     * (as the agent typed it). Exactly one is set.
     */
    public record Target(UUID deviceId, String phone) {
        public static Target device(UUID deviceId) {
            return new Target(deviceId, null);
        }

        public static Target number(String phone) {
            return new Target(null, phone);
        }
    }

    /** The cleaned note, plus the fields only some writes use. */
    public record Write(String lookupId, String note, String caseId, String mode, Integer durationMinutes,
                        String reason, Boolean flagged) {
    }

    /** What an act has to work with, inside the transaction. */
    private record Acting(SupportAgent agent, String note, String msisdn, UUID deviceId, AuditContext ctx,
                          Write write) {
    }

    static final String NO_ACCOUNT_SENTENCE = "No InnBucks account uses this number, so there was no session to end.";

    private final SupportLookupLimiter limiter;
    private final SupportLookupBinding binding;
    private final SupportStaffTargets staffTargets;
    private final SupportActionRepository actions;
    private final SupportAccessLogWriter accessLog;
    private final SupportMetrics metrics;
    private final InnbucksAppSupportSection section;
    private final DeviceSupportService deviceSupport;
    private final CustomerDeviceRepository devices;
    private final UserRepository users;
    private final AccountSessionRevoker sessions;
    private final AuditService auditService;
    private final SupportProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String deploymentCountry;

    public InnbucksAppSupportActions(SupportLookupLimiter limiter, SupportLookupBinding binding,
                                     SupportStaffTargets staffTargets, SupportActionRepository actions,
                                     SupportAccessLogWriter accessLog, SupportMetrics metrics,
                                     InnbucksAppSupportSection section, DeviceSupportService deviceSupport,
                                     CustomerDeviceRepository devices, UserRepository users,
                                     AccountSessionRevoker sessions, AuditService auditService,
                                     SupportProperties properties, PlatformTransactionManager transactionManager,
                                     @Qualifier("supportClock") Clock supportClock,
                                     @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.limiter = limiter;
        this.binding = binding;
        this.staffTargets = staffTargets;
        this.actions = actions;
        this.accessLog = accessLog;
        this.metrics = metrics;
        this.section = section;
        this.deviceSupport = deviceSupport;
        this.devices = devices;
        this.users = users;
        this.sessions = sessions;
        this.auditService = auditService;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = supportClock;
        this.deploymentCountry = deploymentCountry;
    }

    public AppActionResult execute(Op op, SupportAgent agent, Target target, Write write, String idempotencyKey,
                                   AuditContext ctx) {
        SupportSearchService.requireEnabled(properties);
        String clientIp = ctx == null ? null : ctx.ipAddress();
        // 1. Permission — @PreAuthorize ran; re-checked here.
        if (!agent.holds(op.permission())) {
            throw new AccessDeniedException("missing " + op.permission());
        }
        String note = write == null ? null : MfaService.cleanNote(write.note());
        if (note == null) {
            throw SupportPolicyException.noteRequired();
        }
        limiter.acquire(agent);
        String lookupId = write.lookupId();
        String targetId = target.deviceId() != null ? target.deviceId().toString() : null;
        SupportLookupBinding.Bound bound = null;
        String msisdn;
        try {
            // 2. Lookup binding: the lookup first, then the number it must hold.
            bound = binding.bind(agent, lookupId, InnbucksAppSupportSection.NAME, null);
            msisdn = op.deviceTarget ? deviceNumber(target.deviceId()) : normalise(target.phone());
            if (!op.deviceTarget) targetId = msisdn;
            List<String> phones = bound.targets().getOrDefault(InnbucksAppSupportSection.NAME, List.of());
            if (msisdn == null || !phones.contains(msisdn)) {
                throw SupportPolicyException.targetNotFound();
            }
            // 3. Self-action.
            if (agent.isSelf(bound.keys()) || msisdn.equals(agent.phone()) || msisdn.equals(agent.contactPhone())) {
                throw refuseAimed(agent, op, bound, msisdn, SupportPolicyException.selfAction(), ctx);
            }
            // 4. Staff targets — a fresh read.
            Set<UUID> staff = staffTargets.staffAccounts(bound.keys());
            if (!staff.isEmpty() && !agent.holds(PermissionCatalog.SUPPORT_STAFF_TARGETS_MANAGE)) {
                throw refuseAimed(agent, op, bound, msisdn, SupportPolicyException.staffTargetRequiresSupervisor(),
                        ctx);
            }
        } catch (SupportPolicyException e) {
            refusalLog(agent, op.operation(), lookupId, targetId, bound == null ? null : bound.keys(),
                    e.getErrorCode(), clientIp);
            throw e;
        }
        final SupportLookupBinding.Bound b = bound;
        final String number = msisdn;
        final String actedOn = targetId;

        // 5. Idempotency.
        UUID key = ConsoleSupportActions.parseKey(idempotencyKey);
        if (key == null) {
            refusalLog(agent, op.operation(), b.lookupId(), actedOn, b.keys(),
                    SupportPolicyException.IDEMPOTENCY_KEY_REQUIRED, clientIp);
            throw SupportPolicyException.idempotencyKeyRequired();
        }
        Optional<SupportAction> earlier = actions.findByAgentUserUuidAndIdempotencyKey(agent.userUuid(), key);
        if (earlier.isPresent()) {
            return replay(earlier.get(), op, agent, actedOn, number, b, clientIp);
        }

        // 6 + 7. Act and seal in one transaction.
        String next;
        try {
            next = tx.execute(status -> act(op, new Acting(agent, note, number, target.deviceId(), ctx, write),
                    actedOn, key, b));
        } catch (DataIntegrityViolationException race) {
            SupportAction first = actions.findByAgentUserUuidAndIdempotencyKey(agent.userUuid(), key)
                    .orElseThrow(() -> race);
            return replay(first, op, agent, actedOn, number, b, clientIp);
        } catch (SupportPolicyException e) {
            refusalLog(agent, op.operation(), b.lookupId(), actedOn, b.keys(), e.getErrorCode(), clientIp);
            throw e;
        } catch (DeviceSecurityException e) {
            // The device-security guards (already blocked, fraud desk only, …) keep
            // their codes and words; they answer in the support envelope.
            refusalLog(agent, op.operation(), b.lookupId(), actedOn, b.keys(), e.getErrorCode(), clientIp);
            throw asSupportRefusal(e);
        }
        accessLog.bestEffort(row(agent, op.operation(), b.lookupId(), actedOn, b.keys(), "OK", clientIp));
        metrics.lookup("ok");
        return new AppActionResult(SupportAction.OUTCOME_SUCCESS, next, false, phoneView(number));
    }

    /** Inside the transaction. Returns the whatHappensNext sentence. */
    private String act(Op op, Acting a, String actedOn, UUID key, SupportLookupBinding.Bound bound) {
        LocalDateTime now = LocalDateTime.now(clock);
        String customerKey = SupportMasking.customerKey(a.msisdn(), null);
        // The idempotency row FIRST: its unique index makes a concurrent repeat wait here.
        SupportAction row = actions.saveAndFlush(SupportAction.builder()
                .agentUserUuid(a.agent().userUuid())
                .idempotencyKey(key)
                .lookupId(bound.lookupId())
                .section(InnbucksAppSupportSection.NAME)
                .op(op.operation())
                .target(actedOn)
                .customerKey(customerKey)
                .outcome(SupportAction.OUTCOME_PENDING)
                .caseId(a.write().caseId())
                .createdAt(now)
                .build());

        Map<String, Object> metadata = new LinkedHashMap<>();
        String subject = a.agent().subject();
        String next = switch (op) {
            case BLOCK -> deviceSupport.block(a.deviceId(),
                    new BlockRequest(a.write().mode(), a.write().durationMinutes(), a.note()), subject, a.ctx())
                    .whatHappensNext();
            case BAN -> deviceSupport.ban(a.deviceId(), new BanRequest(a.write().reason(), a.note()), subject, a.ctx())
                    .whatHappensNext();
            case UNLOCK -> deviceSupport.unlock(a.deviceId(), a.note(), subject, a.ctx(),
                    a.agent().holds(PermissionCatalog.DEVICE_SECURITY_FRAUD)).whatHappensNext();
            case REVOKE -> deviceSupport.revoke(a.deviceId(), a.note(), subject, a.ctx()).whatHappensNext();
            case RESET_TRUST -> deviceSupport.resetTrust(a.deviceId(), a.note(), subject, a.ctx()).whatHappensNext();
            case FRAUD_FLAG -> fraudFlag(a, metadata);
            case CANCEL_CODES -> cancelCodes(a, metadata);
            case SIGN_OUT -> signOut(a, metadata);
        };

        row.setOutcome(SupportAction.OUTCOME_SUCCESS);
        row.setWhatHappensNext(next);
        row.setCompletedAt(LocalDateTime.now(clock));
        actions.saveAndFlush(row);

        metadata.put("op", op.operation());
        metadata.put("customerKey", customerKey);
        if (a.deviceId() != null) metadata.put("deviceId", a.deviceId().toString());
        metadata.put("lookupId", bound.lookupId());
        metadata.put("idempotencyKey", key.toString());
        metadata.put("section", InnbucksAppSupportSection.NAME);
        metadata.put("outcome", SupportAction.OUTCOME_SUCCESS);
        metadata.put("note", a.note());
        // 7. The seal — LAST, fail-closed.
        auditService.recordRequired(op == Op.SIGN_OUT ? AuditEventType.SUPPORT_SUPER_APP_SIGNED_OUT
                        : AuditEventType.SUPPORT_INNBUCKS_APP_ACTION, subject, AuditService.ACTOR_TYPE_USER,
                "msisdn:" + SupportMasking.phone(a.msisdn()), "CUSTOMER", metadata,
                a.ctx() == null ? AuditContext.none() : a.ctx());
        log.info("Support {} done msisdn={} agent={} lookupId={}", op.operation(), SupportMasking.phone(a.msisdn()),
                a.agent().userUuid(), bound.lookupId());
        return next;
    }

    private String fraudFlag(Acting a, Map<String, Object> metadata) {
        boolean flag = Boolean.TRUE.equals(a.write().flagged());
        deviceSupport.setFraudFlag(a.msisdn(), new FraudFlagRequest(flag, a.note()), a.agent().subject(), a.ctx());
        metadata.put("flagged", flag);
        return flag
                ? "Flagged. Every phone on this number is now trusted for 30 days at most, and its sign-ins score "
                        + "higher. Nothing is sent to the customer."
                : "Fraud flag cleared. Phones keep the trust they have; new trust lasts the usual 90 days.";
    }

    private String cancelCodes(Acting a, Map<String, Object> metadata) {
        int n = deviceSupport.voidCodes(a.msisdn(), a.note(), a.agent().subject(), a.ctx());
        metadata.put("cancelled", n);
        if (n == 0) {
            return "There was no open code for this number, so nothing changed.";
        }
        return n + (n == 1 ? " open code was" : " open codes were") + " cancelled: whoever asked for "
                + (n == 1 ? "it" : "them") + " can no longer use " + (n == 1 ? "it" : "them")
                + ". Tell the caller never to share a code.";
    }

    /**
     * "Sign out of the Super app" (A.3.5): every session of the number's account
     * ends at once — token version bumped and published after commit, every
     * refresh family revoked — and every phone of the number that is not already
     * blocked is blocked until unlocked, with every open code cancelled. The
     * account is never deactivated. Staff and SUPER_ADMIN accounts are refused
     * (a staff account has no sign-in phone, so this is a backstop).
     *
     * <p>Not reached: loyalty's own phone-keyed sessions (Rewards has its own
     * sign-out), and the InnBucks platform's own session on the handset, which
     * ends at its next renewal (within 15 minutes) because the phone is blocked.
     */
    private String signOut(Acting a, Map<String, Object> metadata) {
        int blocked = deviceSupport.blockForSignOut(a.msisdn(), a.note(), a.agent().subject(), a.ctx());
        metadata.put("phonesBlocked", blocked);
        Optional<User> account = users.findByPhoneNumber(a.msisdn());
        String sessionsSentence;
        if (account.isEmpty()) {
            metadata.put("account", false);
            sessionsSentence = NO_ACCOUNT_SENTENCE;
        } else {
            User user = users.lockById(account.get().getId()).orElseThrow(SupportPolicyException::targetNotFound);
            if (user.hasRole(User.Role.SUPER_ADMIN) || staffTargets.isStaffAccount(user)) {
                throw SupportPolicyException.consoleStaffAccount();
            }
            AccountSessionRevoker.Revocation r = sessions.signOutEverywhere(user, "support_super_app_sign_out");
            metadata.put("account", true);
            metadata.put("tokenVersion", r.tokenVersion());
            metadata.put("refreshTokensRevoked", r.refreshTokensRevoked());
            sessionsSentence = "Every session of the customer's InnBucks account has ended; they'll sign in again "
                    + "with a code and their PIN.";
        }
        String phones = blocked == 0
                ? "No phone needed blocking (none was signed in, or each was already blocked)."
                : (blocked == 1 ? "1 phone is" : blocked + " phones are") + " blocked until the customer unlocks it "
                        + "on *569# or support lifts the block, and every open code is cancelled.";
        return sessionsSentence + " " + phones + " The app on a blocked phone stops working at its next renewal, "
                + "within 15 minutes. Rewards has its own sign-out. If the SIM is lost, ask the customer to have it "
                + "blocked at their network too.";
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The number a device belongs to, or null when there is no such device (answered target_not_found). */
    private String deviceNumber(UUID deviceId) {
        if (deviceId == null) return null;
        return devices.findByPublicId(deviceId).map(CustomerDevice::getMsisdn).orElse(null);
    }

    /** E.164, or null for anything that is not a number (answered target_not_found). */
    private String normalise(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return MsisdnValidator.normalizeToE164(phone, deploymentCountry).orElse(null);
    }

    /** The number as the search would show it: a staff account is dropped, exactly as the search drops it. */
    private com.innbucks.userservice.support.dto.SupportDTOs.InnbucksAppPhoneView phoneView(String msisdn) {
        return section.phone(msisdn, users.findByPhoneNumber(msisdn)
                .filter(u -> !staffTargets.isStaffAccount(u))
                .orElse(null));
    }

    private AppActionResult replay(SupportAction earlier, Op op, SupportAgent agent, String actedOn, String msisdn,
                                   SupportLookupBinding.Bound bound, String clientIp) {
        if (!earlier.getOp().equals(op.operation()) || !earlier.getTarget().equals(actedOn)) {
            refusalLog(agent, op.operation(), bound.lookupId(), actedOn, bound.keys(),
                    SupportPolicyException.IDEMPOTENCY_KEY_REUSED, clientIp);
            throw SupportPolicyException.idempotencyKeyReused();
        }
        accessLog.bestEffort(row(agent, op.operation(), bound.lookupId(), actedOn, bound.keys(), "REPLAYED", clientIp));
        metrics.lookup("replayed");
        return new AppActionResult(earlier.getOutcome(), earlier.getWhatHappensNext(), true, phoneView(msisdn));
    }

    static SupportPolicyException asSupportRefusal(DeviceSecurityException e) {
        Map<String, Object> extra = new LinkedHashMap<>(e.getData() == null ? Map.of() : e.getData());
        extra.remove("errorCode");
        return new SupportPolicyException(e.getStatus(), e.getErrorCode(), e.getMessage(), extra,
                e.getRetryAfterSeconds());
    }

    private SupportPolicyException refuseAimed(SupportAgent agent, Op op, SupportLookupBinding.Bound bound,
                                               String msisdn, SupportPolicyException refusal, AuditContext ctx) {
        log.warn("Support {} refused ({}) msisdn={} agent={} lookupId={}", op.operation(), refusal.getErrorCode(),
                SupportMasking.phone(msisdn), agent.userUuid(), bound.lookupId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("op", op.operation());
        metadata.put("lookupId", bound.lookupId());
        auditService.recordFailure(AuditEventType.SUPPORT_ACTION_REFUSED, agent.subject(),
                AuditService.ACTOR_TYPE_USER, "msisdn:" + SupportMasking.phone(msisdn), "CUSTOMER",
                refusal.getErrorCode(), metadata, ctx == null ? AuditContext.none() : ctx);
        return refusal;
    }

    private void refusalLog(SupportAgent agent, String op, String lookupId, String target, SupportCustomerKeys keys,
                            String code, String clientIp) {
        metrics.lookup(code);
        if (SupportPolicyException.LOOKUP_RATE_LIMITED.equals(code)) return;
        String id = lookupId == null ? null : SupportSearchService.truncate(lookupId.strip(), 16);
        accessLog.bestEffort(row(agent, op, id, target, keys, code, clientIp));
    }

    private SupportAccessLog row(SupportAgent agent, String op, String lookupId, String target,
                                 SupportCustomerKeys keys, String outcome, String clientIp) {
        return SupportAccessLog.builder()
                .createdAt(LocalDateTime.now(clock))
                .lookupId(lookupId)
                .op(op)
                .outcome(SupportSearchService.truncate(outcome, 64))
                .agentUserUuid(agent.userUuid())
                .agentSubject(SupportAccessLogWriter.subject(agent.subject()))
                .customerKeys(keys == null ? null : SupportAccessLogWriter.json(keys))
                .sections(InnbucksAppSupportSection.NAME)
                .target(target == null ? null : SupportSearchService.truncate(target, 128))
                .clientIpUntrusted(SupportSearchService.truncate(clientIp, 64))
                .build();
    }
}
