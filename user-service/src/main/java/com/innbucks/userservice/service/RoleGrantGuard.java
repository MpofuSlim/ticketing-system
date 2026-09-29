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
 *       account holds AND every {@link StaffRoles#NAMED} role it holds (or the
 *       wildcard), so a lower holder cannot strip, switch off or open up a
 *       higher one — including one whose authority is its NAME in another
 *       service, as {@code PRODUCT_MANAGER}'s is. → 403
 *       {@code target_not_manageable}.</li>
 *   <li><b>Adding permissions to a role</b> ({@code POST /admin/roles},
 *       {@code PUT /admin/roles/{name}/permissions}): every ADDED code is one the
 *       caller holds, and none is {@link PermissionCatalog#WILDCARD_RESERVED}.
 *       → 400 {@code permission_not_assignable}. Removing codes is never refused
 *       on these grounds (a role must still keep at least one code — that is
 *       validation, not authority).</li>
 * </ul>
 *
 * <p><b>The two sides are compared differently, on purpose.</b> The caller's
 * authority is what their roles EFFECTIVELY grant: a stale code the catalog no
 * longer defines grants them nothing. What they hand out or act against is read
 * as STORED: a stale code on a role or an account counts as a code the caller
 * does not hold (only the wildcard covers it), and a role storing a
 * {@link PermissionCatalog#WILDCARD_RESERVED} code — a legacy grant from before
 * those codes were reserved — can be given to an account by the wildcard only.
 * An unknown code classifies as PLATFORM everywhere else; dropping it here
 * before the comparison would have been the one place it failed open.
 *
 * <p><b>The caller's authority is read LIVE</b> — their current roles resolved
 * through {@link PermissionResolver}, never the token's {@code perms} claim. A
 * token minted before a release that added a permission lacks it, and reading
 * the token would refuse the platform owner a code they plainly hold.
 *
 * <p><b>Fails closed.</b> A caller that does not resolve to an active account
 * (no such subject, a deactivated one, or no caller at all) is refused every
 * role addition and every target check outright — including a role or an
 * account that carries no permission at all, where "holds everything the
 * target holds" would otherwise be vacuously true.
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
     * @param resolved    false when the subject names no active account — such a
     *                    caller is refused everything this guard decides
     * @param roles       the role names on their account now
     * @param permissions what those roles EFFECTIVELY authorize now (wildcard
     *                    expanded, stale codes dropped)
     * @param wildcard    true when one of their roles holds {@code *}
     */
    public record Caller(String subject, boolean resolved, Set<String> roles, Set<String> permissions,
                         boolean wildcard) {
        static Caller nobody(String subject) {
            return new Caller(subject, false, Set.of(), Set.of(), false);
        }

        public boolean holdsRole(String name) {
            return roles.contains(name);
        }

        /**
         * True when the caller holds every one of these STORED codes. The
         * wildcard covers everything, a stale code included; otherwise a stored
         * {@code *} or a code the catalog does not define is never held, because
         * {@link #permissions} only ever contains concrete catalog codes.
         */
        public boolean holdsAll(Collection<String> storedCodes) {
            if (!resolved) return false;
            return wildcard || permissions.containsAll(storedCodes);
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
        return new Caller(subject, true, Set.copyOf(roles), Set.copyOf(PermissionResolver.effective(granted)),
                wildcard);
    }

    /**
     * The permission codes an account's current roles STORE — not expanded, not
     * filtered: a stored {@code *} and a stale code both stay in, so a caller can
     * only cover them by holding the wildcard (see the class javadoc).
     */
    public Set<String> storedGrants(User account) {
        if (account.getRoles() == null || account.getRoles().isEmpty()) return Set.of();
        Set<String> granted = new LinkedHashSet<>();
        for (Role role : roleRepository.findAllByNameIn(account.getRoles())) {
            granted.addAll(grantsOf(role));
        }
        return granted;
    }

    private static Set<String> grantsOf(Role role) {
        return role.getPermissions() == null ? Set.of() : role.getPermissions();
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
     * Refuses unless the caller holds every permission {@code target}'s roles
     * store AND, for every {@link StaffRoles#NAMED} role the target holds, that
     * role or the wildcard — 403 {@code target_not_manageable}
     * ({@code exceeds_your_authority}).
     *
     * <p>The NAME half is the same rule {@link #requireMayAssign} applies: a
     * NAMED role's authority is partly its name in another service
     * ({@code PRODUCT_MANAGER} publishes any event in event-service and reads
     * every organizer's bookings in booking-service, while resolving to one
     * read-only code here). Comparing permissions alone would let a holder of a
     * custom role that happens to cover that one code switch off, strip or
     * reset the 2FA of a product manager they could never have appointed.
     */
    public void requireMayManage(Caller caller, User target) {
        Set<String> held = storedGrants(target);
        String refusal = null;
        if (!caller.resolved()) {
            refusal = "caller does not resolve to an active account";
        } else if (!caller.holdsAll(held)) {
            Set<String> missing = new TreeSet<>(held);
            missing.removeAll(caller.permissions());
            refusal = "missing permissions " + missing;
        } else if (!caller.wildcard()) {
            Set<String> namedNotHeld = new TreeSet<>();
            for (String role : target.getRoles()) {
                if (StaffRoles.isNamed(role) && !caller.holdsRole(role)) namedNotHeld.add(role);
            }
            if (!namedNotHeld.isEmpty()) refusal = "missing named roles " + namedNotHeld;
        }
        if (refusal != null) {
            log.warn("Refused: caller={} may not act on target userId={}: {}",
                    caller.subject(), target.getId(), refusal);
            throw StaffPolicyException.targetNotManageable(StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
        }
    }

    /**
     * Refuses (400 {@code role_not_assignable}) any role in {@code added} the
     * caller may not hand out. Each refused role is named with its reason, in
     * name order, so the console can show every problem in one round trip:
     *
     * <ul>
     *   <li>{@code reserved_to_super_admin} — the role STORES a
     *       {@link PermissionCatalog#WILDCARD_RESERVED} code (a legacy grant
     *       from before those codes were reserved) and the caller is not the
     *       wildcard. Otherwise a holder of such a role could spread
     *       {@code roles:write} / {@code users:roles:write} to any account,
     *       the one path the reserved-code rule did not cover.</li>
     *   <li>{@code exceeds_your_authority} — the role stores a code the caller
     *       does not hold (a stale code counts as not held), or the caller did
     *       not resolve to an active account.</li>
     *   <li>{@code named_role_not_held} — a {@link StaffRoles#NAMED} role the
     *       caller does not hold, and they are not the wildcard.</li>
     * </ul>
     */
    public void requireMayAssign(Caller caller, Collection<Role> added) {
        Map<String, String> refused = new LinkedHashMap<>();
        for (Role role : added.stream().sorted(java.util.Comparator.comparing(Role::getName)).toList()) {
            Set<String> stored = grantsOf(role);
            if (!caller.resolved()) {
                refused.put(role.getName(), StaffPolicyException.REASON_EXCEEDS_YOUR_AUTHORITY);
            } else if (!caller.wildcard() && stored.stream().anyMatch(PermissionCatalog::isReservedToWildcard)) {
                refused.put(role.getName(), StaffPolicyException.REASON_RESERVED_TO_SUPER_ADMIN);
            } else if (!caller.holdsAll(stored)) {
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
