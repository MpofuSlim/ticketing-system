package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.SuperAdminDTOs;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * More than one SUPER_ADMIN (V52) — the ONLY way an account gains or loses
 * SUPER_ADMIN after the seeded owner exists.
 *
 * <p>Owner decisions (2026-10-21):
 * <ol>
 *   <li>There may be several SUPER_ADMIN accounts. A deputy holds the same role
 *       NAME and the same {@code *} wildcard (expanded at mint by
 *       {@code PermissionResolver}), so every service in the fleet — loyalty,
 *       booking, event, seat, marketplace, which check the name — recognises it
 *       with no change outside user-service.</li>
 *   <li>ONLY the seeded platform owner promotes or demotes
 *       ({@link PlatformOwnerGuard}: the caller's live {@code platform_owner}
 *       row, never a token claim).</li>
 *   <li>NOBODY acts on the owner. Every admin path already refuses any
 *       SUPER_ADMIN target (roles, activation, temporary password, 2FA reset,
 *       the staff lifecycle, registration rejection, the support console), so
 *       deputies cannot act on each other or on the owner either; this class
 *       adds the one refusal those cannot express — the owner cannot be
 *       demoted, not even by itself.</li>
 * </ol>
 *
 * <p>{@code PUT /admin/users/{id}/roles} still refuses both granting SUPER_ADMIN
 * and touching a SUPER_ADMIN account. Promotion and demotion happen here and
 * only here, so the rules above live in one place.
 *
 * <p><b>Shape of a change</b> (promote and demote alike), in one transaction:
 * the owner check; {@code SELECT … FOR UPDATE} on the {@code SUPER_ADMIN} role
 * row, then on the target account (the established grant order: roles first,
 * then users, as {@code setRoles} and {@code RoleAdminService.setPermissions}
 * take them); the checks; the role change; a flush; then
 * {@link AccountSessionRevoker#signOutEverywhere} — an atomic
 * {@code token_version} bump through {@link TokenVersionBumper} (published to
 * Redis AFTER commit), every refresh family revoked, device trust and live
 * reset codes cleared. The account is never deactivated: the next sign-in
 * (password plus a second factor) mints the new role set. The REQUIRED audit
 * ({@code SUPER_ADMIN_PROMOTED} / {@code _DEMOTED}) is the last statement, so a
 * change that cannot be recorded is not made ({@code 503 audit_unavailable}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuperAdminService {

    static final String SUPER_ADMIN = User.Role.SUPER_ADMIN.name();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final StaffEligibility staffEligibility;
    private final RoleGrantGuard roleGrantGuard;
    private final AccountSessionRevoker accountSessionRevoker;
    private final AuditService auditService;

    /** Owner first, then the deputies in the System Users order (name, then id). */
    static final Comparator<User> ORDER =
            Comparator.comparingInt((User u) -> u.isPlatformOwner() ? 0 : 1).thenComparing(AdminUserOrder.SYSTEM_USERS);

    /** Every account holding SUPER_ADMIN, the owner first. */
    @Transactional(readOnly = true)
    public List<SuperAdminDTOs.SuperAdminView> list() {
        return userRepository.findByAnyRole(Set.of(SUPER_ADMIN)).stream()
                .sorted(ORDER)
                .map(SuperAdminDTOs.SuperAdminView::of)
                .toList();
    }

    /**
     * Promote {@code userId} to SUPER_ADMIN, keeping its other roles. Refusals,
     * in order: 403 {@code platform_owner_only}; 404 unknown account; 409
     * {@code already_super_admin}; 409 {@code super_admin_target_ineligible}
     * with {@code data.reason} ({@link StaffEligibility#superAdminPromotionBlocker});
     * 409 {@code mfa_not_enrolled}.
     */
    @Transactional
    public User promote(Long userId, String callerSubject, AuditContext auditContext) {
        User owner = platformOwnerGuard.requireOwner(callerSubject, "super_admin_promote");
        roleRepository.lockAllByNameIn(List.of(SUPER_ADMIN));
        User target = userRepository.lockById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        if (target.hasRole(User.Role.SUPER_ADMIN)) {
            throw StaffPolicyException.alreadySuperAdmin();
        }
        Optional<String> blocker = staffEligibility.superAdminPromotionBlocker(target);
        if (blocker.isPresent()) {
            refuse(callerSubject, target, StaffPolicyException.SUPER_ADMIN_TARGET_INELIGIBLE, blocker.get());
            throw StaffPolicyException.superAdminTargetIneligible(blocker.get());
        }
        // A SUPER_ADMIN without a second factor must never exist: MfaPolicy
        // challenges every staff account at sign-in, but an account that has
        // never enrolled would meet its FIRST challenge as a SUPER_ADMIN.
        if (!target.isMfaEnabled()) {
            refuse(callerSubject, target, StaffPolicyException.MFA_NOT_ENROLLED, null);
            throw StaffPolicyException.mfaNotEnrolled();
        }

        Set<String> previous = new LinkedHashSet<>(target.getRoles());
        target.getRoles().add(SUPER_ADMIN);
        return commit(owner, target, previous, AuditEventType.SUPER_ADMIN_PROMOTED, "super_admin_promoted",
                auditContext);
    }

    /**
     * Take SUPER_ADMIN off {@code userId}, keeping its other roles — an account
     * left with none stays active and authorizes nothing. Refusals, in order:
     * 403 {@code platform_owner_only}; 404 unknown account; 403
     * {@code target_not_manageable} ({@code platform_owner}) for the owner,
     * the caller included; 409 {@code not_super_admin}.
     */
    @Transactional
    public User demote(Long userId, String callerSubject, AuditContext auditContext) {
        User owner = platformOwnerGuard.requireOwner(callerSubject, "super_admin_demote");
        roleRepository.lockAllByNameIn(List.of(SUPER_ADMIN));
        User target = userRepository.lockById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        if (target.isPlatformOwner()) {
            refuse(callerSubject, target, StaffPolicyException.TARGET_NOT_MANAGEABLE,
                    StaffPolicyException.REASON_PLATFORM_OWNER);
            throw StaffPolicyException.targetNotManageable(StaffPolicyException.REASON_PLATFORM_OWNER);
        }
        if (!target.hasRole(User.Role.SUPER_ADMIN)) {
            throw StaffPolicyException.notSuperAdmin();
        }

        Set<String> previous = new LinkedHashSet<>(target.getRoles());
        target.getRoles().remove(SUPER_ADMIN);
        return commit(owner, target, previous, AuditEventType.SUPER_ADMIN_DEMOTED, "super_admin_demoted",
                auditContext);
    }

    /** Save, sign out everywhere, then the REQUIRED audit as the last statement. */
    private User commit(User owner, User target, Set<String> previous, AuditEventType type, String reason,
                        AuditContext auditContext) {
        User saved = userRepository.save(target);
        // Flushed BEFORE the bump and the audit, so a constraint failure
        // surfaces here — never after an audit row describing it has committed.
        userRepository.flush();
        AccountSessionRevoker.Revocation revocation = accountSessionRevoker.signOutEverywhere(saved, reason);

        log.info("{} userId={} from={} to={} newTokenVersion={} refreshTokensRevoked={} byUserId={}",
                type, saved.getId(), previous, saved.getRoles(), revocation.tokenVersion(),
                revocation.refreshTokensRevoked(), owner.getId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetEmail", saved.getEmail() == null ? "" : saved.getEmail());
        metadata.put("targetUserUuid", String.valueOf(saved.getUserUuid()));
        metadata.put("previousRoles", previous.stream().sorted().toList());
        metadata.put("newRoles", saved.getRoles().stream().sorted().toList());
        metadata.put("tokenVersion", revocation.tokenVersion());
        metadata.put("refreshTokensRevoked", revocation.refreshTokensRevoked());
        String actor = owner.getEmail() != null ? owner.getEmail() : String.valueOf(owner.getUserUuid());
        auditService.recordRequired(type,
                actor, AuditService.ACTOR_TYPE_USER,
                String.valueOf(saved.getId()), AuditService.TARGET_TYPE_USER,
                metadata, auditContext == null ? AuditContext.none() : auditContext);
        return saved;
    }

    private void refuse(String actor, User target, String failureReason, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", "super_admin");
        if (reason != null) detail.put("reason", reason);
        roleGrantGuard.recordRefusal(actor, target, failureReason, detail);
        log.warn("Refused SUPER_ADMIN change userId={} errorCode={} reason={} by={}",
                target.getId(), failureReason, reason, actor);
    }
}
