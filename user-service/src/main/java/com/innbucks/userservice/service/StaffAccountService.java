package com.innbucks.userservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.config.StaffProvisioningCheck;
import com.innbucks.userservice.dto.StaffDTOs;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.entity.StaffProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.event.StaffInviteRequested;
import com.innbucks.userservice.event.UserDeactivatedEvent;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.AuditEventRepository;
import com.innbucks.userservice.repository.MfaBackupCodeRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffInviteRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionResolver;
import com.innbucks.userservice.security.StaffEmailPolicy;
import com.innbucks.userservice.security.StaffInviteTokens;
import com.innbucks.userservice.security.StaffRoles;
import com.innbucks.userservice.util.HtmlSanitizer;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Staff accounts (V44): {@code /admin/staff}. Accounts created here are staff by
 * construction — an InnBucks address, staff roles only, no organization, no
 * sign-in phone — and cannot sign in until the person redeems the emailed
 * invite, which is what proves the mailbox ({@link StaffInviteService}).
 *
 * <p>Every change here is a change to who holds platform authority, so each one
 * ends with a REQUIRED audit row ({@link AuditService#recordRequired}) as its
 * last statement: a change that cannot be recorded is not made (503
 * {@code audit_unavailable}). Every refusal of the staff rules is written as a
 * {@code STAFF_GRANT_REFUSED} FAILURE row before it is thrown.
 *
 * <p>SUPER_ADMIN is never a target of anything here — it is shown (to a caller
 * holding the wildcard only) and never managed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAccountService {

    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DEACTIVATED = "DEACTIVATED";

    /** The audit event types a staff account's history shows. */
    public static final List<String> AUDIT_TYPES = List.of(
            AuditEventType.STAFF_INVITED.name(), AuditEventType.STAFF_INVITE_RESENT.name(),
            AuditEventType.STAFF_INVITE_ACCEPTED.name(), AuditEventType.STAFF_INVITE_REPLAYED.name(),
            AuditEventType.STAFF_DEACTIVATED.name(), AuditEventType.STAFF_REACTIVATED.name(),
            AuditEventType.STAFF_GRANT_REFUSED.name(),
            AuditEventType.USER_ROLES_CHANGED.name(), AuditEventType.USER_ACTIVATED.name(),
            AuditEventType.USER_DEACTIVATED.name(), AuditEventType.USER_APPROVED.name(),
            AuditEventType.USER_TEMP_PASSWORD_RESET.name(),
            AuditEventType.MFA_ADMIN_RESET.name(), AuditEventType.AUTH_ACCOUNT_UNLOCKED.name());

    static final int MAX_PAGE_SIZE = 100;
    private static final Duration QUOTA_WINDOW = Duration.ofHours(24);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final UserRepository users;
    private final StaffProfileRepository profiles;
    private final StaffInviteRepository invites;
    private final RoleRepository roles;
    private final RoleGrantGuard guard;
    private final StaffEligibility eligibility;
    private final StaffEmailPolicy emailPolicy;
    private final StaffAccountProperties properties;
    private final StaffProvisioningCheck provisioning;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final AccountSessionRevoker sessionRevoker;
    private final TokenVersionBumper tokenVersionBumper;
    private final com.innbucks.userservice.repository.RefreshTokenRepository refreshTokens;
    private final MfaBackupCodeRepository backupCodes;
    private final DeviceTrustService deviceTrust;
    private final AuditService auditService;
    private final AuditEventRepository auditEvents;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final MarketTimeZone marketTimeZone;

    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /** A created or re-invited account and the next step, as the console shows it. */
    public record Outcome(StaffDTOs.StaffView view, String message) {
    }

    // ======================================================================
    // POST /admin/staff
    // ======================================================================

    @Transactional
    public Outcome create(StaffDTOs.CreateRequest req, String callerEmail, AuditContext context) {
        // 1. Provisioning — before anything is created, so no account is ever
        //    left waiting for an invite this cell could not have sent.
        emailPolicy.requireConfigured();
        provisioning.requireInvitesProvisioned();
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        LocalDateTime now = now();

        // 2. Per-caller create quota.
        Set<String> creators = creatorKeys(callerEmail);
        LocalDateTime since = now.minus(QUOTA_WINDOW);
        if (profiles.countCreatedBySince(creators, since) >= properties.getCreateDailyLimit()) {
            LocalDateTime oldest = profiles.oldestCreatedBySince(creators, since);
            long retry = oldest == null ? QUOTA_WINDOW.toSeconds()
                    : Duration.between(now, oldest.plus(QUOTA_WINDOW)).toSeconds();
            guard.recordRefusal(callerEmail, null, StaffPolicyException.STAFF_CREATE_LIMITED,
                    Map.of("limit", properties.getCreateDailyLimit()));
            throw StaffPolicyException.staffCreateLimited(retry, properties.getCreateDailyLimit());
        }

        // 3. The address.
        StaffEmailPolicy.Result address = emailPolicy.evaluate(req.email());
        if (!address.ok()) {
            guard.recordRefusal(callerEmail, null, address.errorCode(),
                    address.reason() == null ? Map.of() : Map.of("reason", address.reason()));
            throw emailPolicy.refusal(address);
        }
        String email = address.normalized();

        // 4. Roles — locked, then every refusal collected into ONE 400.
        Set<String> requested = normaliseRoles(req.roles());
        roles.lockAllByNameIn(new TreeSet<>(requested));
        Map<String, Role> rows = roles.findAllByNameIn(requested).stream()
                .collect(Collectors.toMap(Role::getName, Function.identity(), (a, b) -> a));
        Map<String, String> refused = new TreeMap<>();
        List<Role> candidates = new ArrayList<>();
        for (String name : new TreeSet<>(requested)) {
            Role row = rows.get(name);
            if (User.Role.SUPER_ADMIN.name().equals(name)) {
                refused.put(name, StaffPolicyException.REASON_RESERVED);
            } else if (row == null) {
                refused.put(name, StaffPolicyException.REASON_UNKNOWN);
            } else if (!StaffRoles.isStaffRole(row)) {
                refused.put(name, StaffPolicyException.REASON_NOT_A_STAFF_ROLE);
            } else {
                candidates.add(row);
            }
        }
        refused.putAll(guard.assignRefusals(caller, candidates));
        if (!refused.isEmpty()) {
            guard.recordRefusal(callerEmail, null, StaffPolicyException.ROLE_NOT_ASSIGNABLE,
                    Map.of("roles", new LinkedHashMap<>(refused), "targetEmail", email));
            throw StaffPolicyException.roleNotAssignable(new LinkedHashMap<>(refused));
        }

        // The contact number: any country, never a sign-in identifier.
        String contactPhone = null;
        if (req.phoneNumber() != null && !req.phoneNumber().isBlank()) {
            contactPhone = MsisdnValidator.normalizeToE164(req.phoneNumber(), deploymentCountry)
                    .orElseThrow(StaffPolicyException::invalidPhone);
        }

        // 5. The duplicate — case-insensitively (uk_users_email is case-sensitive).
        if (users.existsByEmailIgnoreCase(email)) {
            throw StaffPolicyException.emailTaken();
        }

        // 6. The account. No usable password, no sign-in phone, email unproven.
        User user = User.builder()
                .firstName(HtmlSanitizer.stripAll(req.firstName()))
                .lastName(HtmlSanitizer.stripAll(req.lastName()))
                .email(email)
                .phoneNumber(null)
                .country(HtmlSanitizer.stripAll(req.country()))
                .homeCountry(deploymentCountry)
                .password(passwordEncoder.encode("!INVITE-" + UUID.randomUUID()))
                .roles(new LinkedHashSet<>(new TreeSet<>(requested)))
                .defaultServices(new LinkedHashSet<>())
                .active(true)
                .approved(true)
                .mfaEnabled(false)
                .mustChangePassword(false)
                .build();
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException raced) {
            // Two creates of one address: only this path writes an on-domain
            // email, always lower-cased, so the case-sensitive unique index
            // catches the second — a 409, not the generic 400.
            log.info("Staff create lost the insert race for one address");
            throw StaffPolicyException.emailTaken();
        }

        // 7. The staff profile.
        profiles.save(StaffProfile.builder()
                .userId(user.getId())
                .contactPhone(contactPhone)
                .adopted(false)
                .createdAt(now)
                .build());

        // 8. The invite, emailed after commit.
        Minted invite = mintInvite(user, callerEmail, now);
        publishInvite(user, invite, callerEmail);
        profiles.flush();

        log.info("Staff account created userId={} roles={} by={}", user.getId(), user.getRoles(), callerEmail);
        // 9. REQUIRED and last.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", email);
        metadata.put("roles", new TreeSet<>(user.getRoles()));
        metadata.put("inviteId", invite.row().getId());
        putNote(metadata, req.note());
        auditService.recordRequired(AuditEventType.STAFF_INVITED, callerEmail, AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER, metadata, context);

        StaffDTOs.StaffView view = view(user, caller).withNext(
                "We're emailing " + user.getFirstName() + " a link to set a password. It works once and expires at "
                        + marketTime(invite.row().getExpiresAt())
                        + ". At first sign-in they will set up two-step verification.");
        return new Outcome(view, "Staff account created. We're emailing an invite to " + email + ".");
    }

    // ======================================================================
    // GET /admin/staff, GET /admin/staff/{id}
    // ======================================================================

    @Transactional(readOnly = true)
    public StaffDTOs.Page<StaffDTOs.StaffView> list(String role, String status, String q, int page, int size,
                                                     String callerEmail) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        String wantedRole = role == null || role.isBlank() ? null : role.trim().toUpperCase(Locale.ROOT);
        String wantedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);

        List<User> candidates = users.findStaffCandidates(guard.staffRoleNames());
        Map<Long, StaffProfile> profileById = profiles.findAllByUserIdIn(
                        candidates.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));

        List<User> rows = candidates.stream()
                .filter(u -> caller.wildcard() || !u.hasRole(User.Role.SUPER_ADMIN))
                .filter(u -> wantedRole == null || u.hasRole(wantedRole))
                .filter(u -> wantedStatus == null || wantedStatus.equals(status(u, profileById.get(u.getId()))))
                .filter(u -> needle == null || matches(u, needle))
                .sorted(Comparator.comparing((User u) -> u.getLastName() == null ? "" : u.getLastName(),
                        String.CASE_INSENSITIVE_ORDER).thenComparing(User::getId))
                .toList();

        int from = (int) Math.min((long) pageNumber * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        List<StaffDTOs.StaffView> content = rows.subList(from, to).stream()
                .map(u -> view(u, profileById.get(u.getId()), caller))
                .toList();
        int totalPages = rows.isEmpty() ? 0 : (rows.size() + pageSize - 1) / pageSize;
        return new StaffDTOs.Page<>(content, rows.size(), totalPages, pageNumber, pageSize);
    }

    @Transactional(readOnly = true)
    public StaffDTOs.StaffView get(Long id, String callerEmail) {
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        return view(requireStaff(id, caller), caller);
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    @Transactional
    public Outcome deactivate(Long id, String note, String callerEmail, AuditContext context) {
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        User user = requireStaff(id, caller);
        requireManageable(caller, user);
        if (sameAccount(user, callerEmail)) {
            throw StaffPolicyException.cannotDeactivateSelf();
        }
        if (!user.isActive()) {
            throw StaffPolicyException.alreadyDeactivated();
        }
        // Atomic active=false + token_version bump, every refresh family, device
        // trust, live reset codes and live invites — one transaction.
        AccountSessionRevoker.Revocation revocation = sessionRevoker.revokeAll(user, "staff_deactivation");
        users.save(user);
        users.flush();
        events.publishEvent(new UserDeactivatedEvent(user.getId(), user.getFirstName(), user.getEmail(),
                user.getPhoneNumber(), user.hasRole(User.Role.CUSTOMER)));
        log.info("Staff account deactivated userId={} by={}", user.getId(), callerEmail);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", user.getEmail());
        metadata.put("roles", new TreeSet<>(user.getRoles()));
        metadata.put("tokenVersion", revocation.tokenVersion());
        metadata.put("refreshTokensRevoked", revocation.refreshTokensRevoked());
        metadata.put("resetCodesDeleted", revocation.resetCodesDeleted());
        putNote(metadata, note);
        auditService.recordRequired(AuditEventType.STAFF_DEACTIVATED, callerEmail, AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER, metadata, context);
        return new Outcome(view(user, caller).withNext(user.getFirstName()
                + " was signed out everywhere and can't sign in until reactivated."), "Staff account deactivated");
    }

    /**
     * Reactivation does NOT restore the old credentials — a deactivation is often
     * a response to a compromise. The account comes back INVITED: unusable
     * password, 2FA cleared, email unproven, and a new invite needed.
     */
    @Transactional
    public Outcome reactivate(Long id, String note, String callerEmail, AuditContext context) {
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        User user = requireStaff(id, caller);
        requireManageable(caller, user);
        if (user.isActive()) {
            throw StaffPolicyException.notDeactivated();
        }
        emailPolicy.requireConfigured();
        Optional<StaffProfile> existing = eligibility.profileOf(user);
        if (existing.isPresent()) {
            if (!emailPolicy.evaluate(user.getEmail()).ok()) {
                guard.recordRefusal(callerEmail, user, StaffEmailPolicy.EMAIL_DOMAIN_NOT_ALLOWED,
                        Map.of("site", "staff_reactivate"));
                throw emailPolicy.domainNotAllowed();
            }
        } else {
            Optional<String> blocker = eligibility.adoptionBlocker(user);
            if (blocker.isPresent()) {
                guard.recordRefusal(callerEmail, user, StaffPolicyException.ADOPTION_BLOCKED,
                        Map.of("reason", blocker.get(), "site", "staff_reactivate"));
                throw StaffPolicyException.adoptionBlocked(blocker.get());
            }
        }
        LocalDateTime now = now();
        user.setActive(true);
        resetCredentials(user);
        user.setEmailVerifiedAt(null);
        StaffProfile profile = existing.orElseGet(() -> StaffProfile.builder()
                .userId(user.getId()).adopted(true).createdAt(now).build());
        profile.setInviteAcceptedAt(null);
        profiles.save(profile);
        users.save(user);
        invites.revokeLive(user.getId(), StaffInvite.REVOKED_REACTIVATED, now);
        // Bump + revoke every refresh family + device trust + reset codes.
        AccountSessionRevoker.Revocation revocation = sessionRevoker.sweepOnReactivation(user);
        users.flush();
        log.info("Staff account reactivated (INVITED) userId={} by={}", user.getId(), callerEmail);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", user.getEmail());
        metadata.put("roles", new TreeSet<>(user.getRoles()));
        metadata.put("adopted", existing.isEmpty());
        metadata.put("tokenVersion", revocation.tokenVersion());
        putNote(metadata, note);
        auditService.recordRequired(AuditEventType.STAFF_REACTIVATED, callerEmail, AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER, metadata, context);
        return new Outcome(view(user, profile, caller).withNext("Send a new invite so they can set a password."),
                "Staff account reactivated");
    }

    /**
     * Two cases. An INVITED account: live invites revoked ({@code SUPERSEDED})
     * and a new one minted. An adoptable LEGACY account (no profile): adopted —
     * a profile is created, which makes it INVITED so it cannot sign in until the
     * invite is redeemed (any squatter is locked out from this moment), its
     * sessions end, and an invite is minted.
     */
    @Transactional
    public Outcome resendInvite(Long id, String note, String callerEmail, AuditContext context) {
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        User user = requireStaff(id, caller);
        requireManageable(caller, user);
        if (!user.isActive()) {
            throw StaffPolicyException.inviteNotPending(true);
        }
        Optional<StaffProfile> existing = eligibility.profileOf(user);
        if (existing.isPresent() && !existing.get().isInvitePending()) {
            throw StaffPolicyException.inviteNotPending(false);
        }
        emailPolicy.requireConfigured();
        provisioning.requireInvitesProvisioned();
        boolean adoption = existing.isEmpty();
        if (adoption) {
            Optional<String> blocker = eligibility.adoptionBlocker(user);
            if (blocker.isPresent()) {
                guard.recordRefusal(callerEmail, user, StaffPolicyException.ADOPTION_BLOCKED,
                        Map.of("reason", blocker.get(), "site", "staff_resend_invite"));
                throw StaffPolicyException.adoptionBlocked(blocker.get());
            }
        } else if (!emailPolicy.evaluate(user.getEmail()).ok()) {
            guard.recordRefusal(callerEmail, user, StaffEmailPolicy.EMAIL_DOMAIN_NOT_ALLOWED,
                    Map.of("site", "staff_resend_invite"));
            throw emailPolicy.domainNotAllowed();
        }

        LocalDateTime now = now();
        LocalDateTime since = now.minus(QUOTA_WINDOW);
        if (invites.countByUserIdAndCreatedAtAfter(user.getId(), since) >= properties.getInviteResendLimit()) {
            long retry = invites.findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(user.getId(), since)
                    .map(i -> Duration.between(now, i.getCreatedAt().plus(QUOTA_WINDOW)).toSeconds())
                    .orElse(QUOTA_WINDOW.toSeconds());
            guard.recordRefusal(callerEmail, user, StaffPolicyException.INVITE_RESEND_LIMITED,
                    Map.of("limit", properties.getInviteResendLimit()));
            throw StaffPolicyException.inviteResendLimited(retry, properties.getInviteResendLimit());
        }

        StaffProfile profile;
        if (adoption) {
            profile = profiles.save(StaffProfile.builder()
                    .userId(user.getId()).adopted(true).createdAt(now).build());
            // The account's existing sessions end NOW — whoever holds them (a
            // squatter on a console-created account included) is out.
            tokenVersionBumper.bump(user);
            refreshTokens.revokeAllForUser(user.getId(), Instant.now());
            log.info("Legacy staff account adopted userId={} by={}", user.getId(), callerEmail);
        } else {
            profile = existing.get();
            invites.revokeLive(user.getId(), StaffInvite.REVOKED_SUPERSEDED, now);
        }
        Minted invite = mintInvite(user, callerEmail, now);
        publishInvite(user, invite, callerEmail);
        invites.flush();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", user.getEmail());
        metadata.put("roles", new TreeSet<>(user.getRoles()));
        metadata.put("adoption", adoption);
        metadata.put("inviteId", invite.row().getId());
        putNote(metadata, note);
        auditService.recordRequired(AuditEventType.STAFF_INVITE_RESENT, callerEmail, AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER, metadata, context);

        String expires = marketTime(invite.row().getExpiresAt());
        String next = adoption
                ? user.getFirstName() + "'s existing sessions were ended. We're emailing a link to set a new "
                        + "password; they can't sign in until they use it. It works once and expires at " + expires + "."
                : "We're emailing " + user.getFirstName() + " a new link to set a password. It works once and "
                        + "expires at " + expires + ". Earlier links no longer work.";
        return new Outcome(view(user, profile, caller).withNext(next), "Invite sent to " + user.getEmail() + ".");
    }

    // ======================================================================
    // GET /admin/staff/{id}/audit
    // ======================================================================

    @Transactional(readOnly = true)
    public StaffDTOs.Page<StaffDTOs.AuditEntry> audit(Long id, int page, int size, String callerEmail) {
        RoleGrantGuard.Caller caller = guard.resolveCaller(callerEmail);
        User user = requireStaff(id, caller);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        org.springframework.data.domain.Page<AuditEvent> found = auditEvents.findForTarget(
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER, AUDIT_TYPES,
                PageRequest.of(Math.max(page, 0), pageSize));
        List<StaffDTOs.AuditEntry> content = found.getContent().stream().map(this::auditEntry).toList();
        return new StaffDTOs.Page<>(content, found.getTotalElements(), found.getTotalPages(), found.getNumber(),
                found.getSize());
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    /** A minted invite: the row and the raw token, which exists nowhere else. */
    record Minted(StaffInvite row, String rawToken) {
        @Override
        public String toString() {
            return "Minted[inviteId=" + (row == null ? null : row.getId()) + ", token=<redacted>]";
        }
    }

    private Minted mintInvite(User user, String callerEmail, LocalDateTime now) {
        String raw = StaffInviteTokens.generate();
        StaffInvite row = invites.save(StaffInvite.builder()
                .userId(user.getId())
                .tokenHash(StaffInviteTokens.hash(raw))
                .sentToEmail(user.getEmail())
                .createdAt(now)
                .createdByEmail(callerEmail == null ? "system" : callerEmail)
                .expiresAt(now.plus(properties.getInviteTtl()))
                .deliveryStatus(StaffInvite.Delivery.PENDING.name())
                .build());
        return new Minted(row, raw);
    }

    private void publishInvite(User user, Minted invite, String callerEmail) {
        events.publishEvent(new StaffInviteRequested(invite.row().getId(), user.getEmail(), user.getFirstName(),
                invite.rawToken(), user.getRoles().stream().sorted().toList(), callerEmail,
                invite.row().getExpiresAt()));
    }

    /** Unusable password, 2FA and its backup codes cleared, device trust cleared, lockouts cleared. */
    private void resetCredentials(User user) {
        user.setPassword(passwordEncoder.encode("!INVITE-" + UUID.randomUUID()));
        user.setMustChangePassword(false);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        backupCodes.deleteAllForUser(user.getId());
        deviceTrust.clearTrustForUser(user.getId());
    }

    /**
     * The account behind {@code id} when it is a staff account the caller may
     * see; 404 {@code staff_not_found} otherwise — including a SUPER_ADMIN for a
     * caller not holding the wildcard, so the directory is no oracle for it.
     */
    private User requireStaff(Long id, RoleGrantGuard.Caller caller) {
        User user = id == null ? null : users.findById(id).orElse(null);
        if (user == null || !eligibility.isStaffAccount(user)
                || (user.hasRole(User.Role.SUPER_ADMIN) && !caller.wildcard())) {
            throw StaffPolicyException.staffNotFound();
        }
        return user;
    }

    /** 403 {@code target_not_manageable}: a SUPER_ADMIN target ({@code super_admin}) or caller ⊉ target. */
    private void requireManageable(RoleGrantGuard.Caller caller, User user) {
        if (user.hasRole(User.Role.SUPER_ADMIN)) {
            guard.recordRefusal(caller.subject(), user, StaffPolicyException.TARGET_NOT_MANAGEABLE,
                    Map.of("reason", StaffPolicyException.REASON_SUPER_ADMIN));
            throw StaffPolicyException.targetNotManageable(StaffPolicyException.REASON_SUPER_ADMIN);
        }
        guard.requireMayManage(caller, user);
    }

    private static boolean sameAccount(User user, String callerEmail) {
        return callerEmail != null && user.getEmail() != null && user.getEmail().equalsIgnoreCase(callerEmail);
    }

    /** How the create quota recognises the caller: JPA auditing stamps their uuid, else their email. */
    private Set<String> creatorKeys(String callerEmail) {
        Set<String> keys = new LinkedHashSet<>();
        if (callerEmail != null) {
            keys.add(callerEmail);
            users.findByEmail(callerEmail).map(User::getUserUuid).ifPresent(u -> keys.add(u.toString()));
        }
        if (keys.isEmpty()) keys.add("system");
        return keys;
    }

    private static Set<String> normaliseRoles(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String role : raw) {
                if (role != null && !role.isBlank()) out.add(role.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean matches(User u, String needle) {
        return contains(u.getEmail(), needle) || contains(u.getFirstName(), needle)
                || contains(u.getLastName(), needle)
                || contains(((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                        + (u.getLastName() == null ? "" : u.getLastName())), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    static String status(User u, StaffProfile profile) {
        if (!u.isActive()) return STATUS_DEACTIVATED;
        if (profile != null && profile.isInvitePending()) return STATUS_INVITED;
        return STATUS_ACTIVE;
    }

    private StaffDTOs.StaffView view(User user, RoleGrantGuard.Caller caller) {
        return view(user, eligibility.profileOf(user).orElse(null), caller);
    }

    StaffDTOs.StaffView view(User user, StaffProfile profile, RoleGrantGuard.Caller caller) {
        String status = status(user, profile);
        Instant nowInstant = Instant.now();
        boolean lockedOut = (user.getLockedUntil() != null && user.getLockedUntil().isAfter(nowInstant))
                || (user.getMfaLockedUntil() != null && user.getMfaLockedUntil().isAfter(nowInstant));
        StaffDTOs.InviteState invite = null;
        if (STATUS_INVITED.equals(status)) {
            invite = invites.findLive(user.getId()).stream().findFirst()
                    .map(i -> new StaffDTOs.InviteState(i.getSentToEmail(), i.getExpiresAt(), i.getDeliveryStatus()))
                    .orElse(null);
        }
        Boolean adoptable = null;
        String blocker = null;
        if (profile == null) {
            if (!user.hasRole(User.Role.SUPER_ADMIN)) {
                blocker = eligibility.adoptionBlocker(user).orElse(null);
                adoptable = user.isActive() && blocker == null;
            } else {
                adoptable = false;
            }
        }
        boolean manageable = !user.hasRole(User.Role.SUPER_ADMIN) && guard.mayManage(caller, user);
        List<String> roleNames = user.getRoles().stream().sorted().toList();
        List<String> permissions = permissionResolver.resolve(roleNames).stream().sorted().toList();
        String phone = profile != null && profile.getContactPhone() != null
                ? profile.getContactPhone() : user.getPhoneNumber();
        return new StaffDTOs.StaffView(user.getId(), user.getUserUuid(), user.getFirstName(), user.getLastName(),
                user.getEmail(), phone, user.getCountry(), roleNames, permissions, status,
                user.getEmailVerifiedAt() != null, emailPolicy.evaluate(user.getEmail()).ok(),
                user.isMfaEnabled(), lockedOut, user.getLastSignInAt(), user.getCreatedAt(),
                createdBy(user.getCreatedBy()), manageable, invite, adoptable, blocker, null);
    }

    /** {@code users.created_by} holds the creator's uuid (JPA auditing) or, without one, their email. */
    private StaffDTOs.CreatedBy createdBy(String stamped) {
        if (stamped == null || stamped.isBlank()) return null;
        if (stamped.contains("@")) return new StaffDTOs.CreatedBy(stamped);
        try {
            return users.findByUserUuid(UUID.fromString(stamped))
                    .map(u -> new StaffDTOs.CreatedBy(u.getEmail()))
                    .orElse(null);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private StaffDTOs.AuditEntry auditEntry(AuditEvent e) {
        JsonNode metadata = readMetadata(e.getMetadata());
        String note = metadata.hasNonNull("note") ? metadata.get("note").asText() : null;
        LocalDateTime at = e.getOccurredAt() == null ? null : LocalDateTime.ofInstant(e.getOccurredAt(), ZoneOffset.UTC);
        return new StaffDTOs.AuditEntry(at, e.getEventType(), e.getOutcome(), e.getActorId(),
                summary(e.getEventType(), e.getFailureReason(), metadata), note);
    }

    private JsonNode readMetadata(String raw) {
        if (raw == null || raw.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    /** One server-rendered sentence per audit row. Never quotes hmacs or tokens. */
    static String summary(String type, String failureReason, JsonNode m) {
        String roles = list(m.get("roles"));
        return switch (type == null ? "" : type) {
            case "STAFF_INVITED" -> "Created with role" + (m.path("roles").size() == 1 ? " " : "s ") + roles
                    + " and invited by email.";
            case "STAFF_INVITE_RESENT" -> m.path("adoption").asBoolean(false)
                    ? "Adopted as a staff account: sessions ended and an invite emailed."
                    : "A new invite was emailed; earlier links stopped working.";
            case "STAFF_INVITE_ACCEPTED" -> "Invite accepted: password set and email confirmed.";
            case "STAFF_INVITE_REPLAYED" -> "An invite link that was already used, revoked or expired was presented.";
            case "STAFF_DEACTIVATED" -> "Deactivated and signed out everywhere.";
            case "STAFF_REACTIVATED" -> "Reactivated. A new invite is needed before they can sign in.";
            case "STAFF_GRANT_REFUSED" -> "Refused: " + (failureReason == null ? "staff rules"
                    : failureReason.replace('_', ' ')) + ".";
            case "USER_ROLES_CHANGED" -> "Roles changed from " + list(m.get("previousRoles")) + " to "
                    + list(m.get("newRoles")) + ".";
            case "USER_ACTIVATED" -> "Activated.";
            case "USER_DEACTIVATED" -> "Deactivated.";
            case "USER_APPROVED" -> "Approved.";
            case "USER_TEMP_PASSWORD_RESET" -> "A temporary password was issued.";
            case "MFA_ADMIN_RESET" -> "Two-step verification was reset by an administrator.";
            case "AUTH_ACCOUNT_UNLOCKED" -> "Sign-in lockout lifted.";
            default -> type;
        };
    }

    private static String list(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return "none";
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return String.join(", ", values);
    }

    private static void putNote(Map<String, Object> metadata, String note) {
        String clean = MfaService.cleanNote(note);
        if (clean != null) metadata.put("note", clean);
    }

    /** "10.15 on 2 Oct", in the market's local time. */
    private String marketTime(LocalDateTime utc) {
        OffsetDateTime local = marketTimeZone.atMarketFromUtc(utc);
        return TIME.format(local) + " on " + DAY.format(local);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

}
