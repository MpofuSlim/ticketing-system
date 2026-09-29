package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import com.innbucks.userservice.security.PermissionResolver;
import com.innbucks.userservice.security.StaffRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * No-escalation: nobody hands out, edits into a role, or takes away more
 * authority than they hold themselves.
 *
 * <p>Before this, anyone holding {@code users:roles:write} could give any role
 * to anyone — themselves included — and anyone holding {@code roles:write} could
 * add any permission to any role, including one they held. The endpoint
 * permission said who may administer roles; nothing bounded WHAT they could hand
 * out. The rules, each enforced here and called from the one service method that
 * owns the change:
 *
 * <ul>
 *   <li><b>Adding a role to an account</b> ({@code PUT /admin/users/{id}/roles}):
 *       the caller holds every permission the role grants; a
 *       {@link StaffRoles#NAMED} role additionally needs the caller to hold that
 *       role or the wildcard, because other services act on the NAME.
 *       → 400 {@code role_not_assignable}.</li>
 *   <li><b>Removing a role</b>, <b>deactivating a staff-role holder</b>,
 *       <b>resetting someone's 2FA</b>: the caller holds every permission the
 *       account holds, so a lower holder cannot strip, switch off or open up a
 *       higher one. → 403 {@code target_not_manageable}.</li>
 *   <li><b>Adding permissions to a role</b> ({@code POST /admin/roles},
 *       {@code PUT /admin/roles/{name}/permissions}): every ADDED code is one the
 *       caller holds, and none is {@link PermissionCatalog#WILDCARD_RESERVED}.
 *       → 400 {@code permission_not_assignable}. An edit that only removes codes
 *       is never refused.</li>
 * </ul>
 *
 * <p><b>The caller's authority is read LIVE</b> — their current roles resolved
 * through {@link PermissionResolver}, never the token's {@code perms} claim. A
 * token minted before a release that added a permission lacks it, and reading
 * the token would refuse the platform owner a code they plainly hold.
 *
 * <p><b>Fails closed.</b> A caller that does not resolve to an active account
 * (no such subject, a deactivated one, or no caller at all) holds nothing, so
 * every addition and every target check refuses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleGrantGuard {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    /**
     * The acting administrator, resolved live.
     *
     * @param subject     the JWT subject ({@code Authentication#getName()}): the
     *                    email, or the phone for an account without one
     * @param roles       the role names on their account now
     * @param permissions what those roles authorize now (wildcard expanded)
     * @param wildcard    true when one of their roles holds {@code *}
     */
    public record Caller(String subject, Set<String> roles, Set<String> permissions, boolean wildcard) {
        static Caller nobody(String subject) {
            return new Caller(subject, Set.of(), Set.of(), false);
        }

        public boolean holdsRole(String name) {
            return roles.contains(name);
        }
    }

    /** Resolves the caller by JWT subject — email, or phone for an account with none. */
    public Caller resolveCaller(String subject) {
        if (subject == null || subject.isBlank()) {
            return Caller.nobody(subject);
        }
        Optional<User> account = subject.contains("@")
                ? userRepository.findByEmail(subject)
                : userRepository.findByPhoneNumber(subject);
        if (account.isEmpty() || !account.get().isActive()) {
            log.warn("Role-grant caller does not resolve to an active account subject={} — treated as holding nothing",
                    subject);
            return Caller.nobody(subject);
        }
        User user = account.get();
        Set<String> roles = new LinkedHashSet<>(user.getRoles());
        List<Role> rows = roles.isEmpty() ? List.of() : roleRepository.findAllByNameIn(roles);
        boolean wildcard = false;
        Set<String> granted = new LinkedHashSet<>();
        for (Role role : rows) {
            if (role.getPermissions() == null) continue;
            if (role.getPermissions().contains(PermissionCatalog.WILDCARD)) wildcard = true;
            granted.addAll(role.getPermissions());
        }
        return new Caller(subject, Set.copyOf(roles), Set.copyOf(PermissionResolver.effective(granted)), wildcard);
    }

    /** What an account's current roles authorize (wildcard expanded, unknown codes dropped). */
    public Set<String> resolvedPermissions(User account) {
        if (account.getRoles() == null || account.getRoles().isEmpty()) return Set.of();
        Set<String> granted = new LinkedHashSet<>();
        for (Role role : roleRepository.findAllByNameIn(account.getRoles())) {
            if (role.getPermissions() != null) granted.addAll(role.getPermissions());
        }
        return PermissionResolver.effective(granted);
    }

    /**
     * True when the account holds at least one staff role (see {@link StaffRoles}):
     * a NAMED one, or a role row granting the wildcard or a PLATFORM permission.
     */
    public boolean holdsStaffRole(User account) {
        if (account.getRoles() == null || account.getRoles().isEmpty()) return false;
        for (String name : account.getRoles()) {
            if (StaffRoles.isNamed(name)) return true;
        }
        for (Role role : roleRepository.findAllByNameIn(account.getRoles())) {
            if (StaffRoles.isStaffRole(role)) return true;
        }
        return false;
    }

    /**
     * Refuses unless the caller holds every permission {@code target} holds —
     * 403 {@code target_not_manageable} ({@code exceeds_your_authority}).
     */
    public void requireMayManage(Caller caller, User target) {
        Set<String> held = resolvedPermissions(target);
        if (!caller.permissions().containsAll(held)) {
            Set<String> missing = new TreeSet<>(held);
            missing.removeAll(caller.permissions());
            log.warn("Refused: caller={} does not hold everything target userId={} holds, missing={}",
                    caller.subject(), target.getId(), missing);
            throw StaffPolicyException.targetNotManageable(StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
        }
    }

    /**
     * Refuses (400 {@code role_not_assignable}) any role in {@code added} the
     * caller may not hand out. Each refused role is named with its reason, in
     * name order, so the console can show every problem in one round trip.
     */
    public void requireMayAssign(Caller caller, Collection<Role> added) {
        Map<String, String> refused = new LinkedHashMap<>();
        for (Role role : added.stream().sorted(java.util.Comparator.comparing(Role::getName)).toList()) {
            if (!caller.permissions().containsAll(PermissionResolver.effective(role.getPermissions()))) {
                refused.put(role.getName(), StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
            } else if (StaffRoles.isNamed(role.getName())
                    && !caller.wildcard() && !caller.holdsRole(role.getName())) {
                refused.put(role.getName(), StaffPolicyException.REASON_NAMED_ROLE_NOT_HELD);
            }
        }
        if (!refused.isEmpty()) {
            log.warn("Refused role grant by caller={}: {}", caller.subject(), refused);
            throw StaffPolicyException.roleNotAssignable(refused);
        }
    }

    /**
     * Refuses (400 {@code permission_not_assignable}) any ADDED code that is
     * reserved to the wildcard — whoever the caller is, SUPER_ADMIN included — or
     * that the caller does not hold. Reserved wins when both apply: it is the
     * answer that does not change with who asks.
     */
    public void requireMayGrant(Caller caller, Collection<String> addedCodes) {
        Map<String, String> refused = new LinkedHashMap<>();
        for (String code : new TreeSet<>(addedCodes)) {
            if (PermissionCatalog.isReservedToWildcard(code)) {
                refused.put(code, StaffPolicyException.REASON_RESERVED_TO_SUPER_ADMIN);
            } else if (!caller.permissions().contains(code)) {
                refused.put(code, StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
            }
        }
        if (!refused.isEmpty()) {
            log.warn("Refused permission grant by caller={}: {}", caller.subject(), refused);
            throw StaffPolicyException.permissionNotAssignable(refused);
        }
    }
}
