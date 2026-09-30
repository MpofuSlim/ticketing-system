package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.security.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Create, edit and delete roles (V35).
 *
 * <p>Every write here is audited, and the audit is REQUIRED
 * ({@link AuditService#recordRequired}): a role change is a change to who can do
 * what, which is exactly the class of event {@code audit_events} exists to make
 * tamper-evident. Granting yourself a permission and quietly ungranting it is
 * otherwise invisible — so a change whose audit row cannot be written is not
 * made ({@code 503 audit_unavailable}).
 *
 * <p>No-escalation ({@link RoleGrantGuard}): a code ADDED to a role must be one
 * the caller holds, and never one reserved to the wildcard
 * ({@link PermissionCatalog#WILDCARD_RESERVED}); removing codes is never
 * refused on authority or reserved-code grounds (a role must still keep at
 * least one code — that is validation). Removing a PLATFORM code signs every
 * holder out at once (see {@link #setPermissions}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    /**
     * Role names are {@code UPPER_SNAKE_CASE}, 2–64 chars, starting with a
     * letter.
     *
     * <p>Not cosmetic. Spring Security derives an authority by prefixing
     * {@code ROLE_}, and the name travels in a JWT claim and a
     * {@code @PreAuthorize} string. A name with a space, a quote or a lowercase
     * letter produces an authority that no {@code hasRole(…)} can be written
     * against — it would save fine and then never match, which reads as "the
     * role I created doesn't work" with nothing to point at.
     */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    /**
     * Names no custom role may take. {@code ADMIN} was never a platform role (V3
     * rewrote the legacy rows to SUPER_ADMIN, and no check anywhere names it), so
     * a role called that would read as authority it does not have; a bare
     * {@code CALL_CENTER} and any {@code CALL_CENTRE…} spelling would sit beside
     * the built-in call-center roles and be picked by mistake. The built-in
     * names themselves are taken by their rows (409).
     */
    static boolean isReservedName(String normalized) {
        return "ADMIN".equals(normalized)
                || "CALL_CENTER".equals(normalized)
                || normalized.startsWith("CALL_CENTRE");
    }

    private final RoleRepository roleRepository;
    private final AuditService auditService;
    /** No-escalation on what a role may be given ({@link RoleGrantGuard}). */
    private final RoleGrantGuard roleGrantGuard;
    /**
     * Ends the sessions of every holder of a role that loses a PLATFORM
     * permission — the one writer of {@code token_version}.
     */
    private final TokenVersionBumper tokenVersionBumper;
    /**
     * Holder eligibility (V44): a role that gains platform authority may only do
     * so while every account holding it is staff-eligible.
     */
    private final StaffEligibility staffEligibility;

    @Transactional(readOnly = true)
    public List<Role> list() {
        return roleRepository.findAllByOrderByBuiltinDescNameAsc();
    }

    @Transactional(readOnly = true)
    public Role get(String name) {
        return roleRepository.findById(normalize(name))
                .orElseThrow(() -> new NotFoundException("Role not found: " + name));
    }

    /** The permission catalog an operator composes roles from. */
    @Transactional(readOnly = true)
    public Map<String, String> permissionCatalog() {
        return PermissionCatalog.ALL;
    }

    @Transactional
    public Role create(String name, String description, Collection<String> permissions,
                       String adminEmail, AuditContext auditContext) {
        String normalized = normalize(name);

        if (!VALID_NAME.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Role name must be UPPER_SNAKE_CASE, 2-64 characters, starting with a letter "
                            + "(e.g. REFUND_OFFICER). Got: " + name);
        }
        if (isReservedName(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reservedNameMessage(normalized));
        }
        if (roleRepository.existsById(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A role named " + normalized + " already exists.");
        }

        Set<String> granted = validatePermissions(permissions, Set.of());
        // Every code on a new role is an ADDED code.
        roleGrantGuard.requireMayGrant(roleGrantGuard.resolveCaller(adminEmail), granted, normalized);
        // user_roles has no FK to roles, so accounts may already hold this name
        // as an orphan string — and a new STAFF role would hand every one of
        // them platform authority the moment it is saved. They must all be
        // staff-eligible first (400 staff_holders_ineligible).
        if (com.innbucks.userservice.security.StaffRoles.isStaffRole(normalized, granted)) {
            staffEligibility.requireHoldersEligible(normalized, adminEmail);
        }

        Role role = Role.builder()
                .name(normalized)
                .description(description == null ? "" : description.trim())
                .builtin(false)
                .createdBy(adminEmail)
                .permissions(granted)
                .build();
        Role saved = roleRepository.save(role);
        // Flushed BEFORE the required audit, so any constraint failure surfaces
        // here — never after an audit row describing it has committed.
        roleRepository.flush();

        log.info("Role created name={} permissions={} by={}",
                normalized, granted, adminEmail == null ? "system" : adminEmail);
        // Last statement: a grant that cannot be recorded is not made.
        audit(AuditEventType.ROLE_CREATED, saved, adminEmail, auditContext,
                Map.of("permissions", List.copyOf(granted)));
        return saved;
    }

    private static String reservedNameMessage(String normalized) {
        if ("ADMIN".equals(normalized)) {
            return "The role name ADMIN is reserved: it has never been a platform role, so a role called that "
                    + "would read as authority it does not have. Use a built-in role (PRODUCT_OFFICER, "
                    + "PRODUCT_MANAGER, CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, FRAUD_DESK) or choose "
                    + "another name.";
        }
        return "The role name " + normalized + " is reserved: the call-center roles are built in and spelled "
                + "CALL_CENTER (CALL_CENTER_AGENT, CALL_CENTER_SUPERVISOR, with FRAUD_DESK as an add-on). "
                + "Assign those, or choose another name.";
    }

    /**
     * Replace a role's permission set. Allowed on built-in roles too — editing
     * what {@code MERCHANT_ADMIN} can do is a legitimate and expected operation,
     * and it is only the NAME of a built-in that code depends on.
     *
     * <p><b>Added codes</b> must each be held by the caller and never be reserved
     * to the wildcard ({@link RoleGrantGuard#requireMayGrant}). <b>Removing codes
     * is never refused on authority or reserved-code grounds</b> — taking
     * authority away must always be possible, including from a role an earlier
     * release let hold a code that is reserved today. The one limit is
     * validation, not authority: a role must keep at least one code (400), so
     * emptying a role takes deleting it instead.
     *
     * <p><b>Removing a PLATFORM code signs every holder out at once</b>: one
     * atomic {@code token_version} bump across everyone holding the role, each
     * new version published to the shared Redis after commit (pipelined, in
     * batches — {@link TokenVersionBumper#bumpAllHolding}). A support agent who
     * loses {@code device-security:manage} must not keep using it for the rest of
     * their access token's life. <b>The cost is the ROLE's holder count</b>, not
     * any staff population: a PLATFORM (or stale) code taken off a widely held
     * business role signs every holder out — every business, for
     * {@code MERCHANT_ADMIN}. Correct, since the code was on their tokens, and
     * WARNed + counted above {@link TokenVersionBumper#LARGE_BULK_BUMP_THRESHOLD}.
     * <b>Removing only TENANT codes does not bump</b>: the change reaches each
     * holder at their next refresh (at most the access-token lifetime, 15
     * minutes), so trimming what every MERCHANT_ADMIN can do does not sign out
     * every business on the platform at once.
     */
    @Transactional
    public Role setPermissions(String name, Collection<String> permissions,
                               String adminEmail, AuditContext auditContext) {
        // Row lock FIRST (V44), before the role is read: a concurrent
        // PUT /admin/users/{id}/roles adding this role waits for this commit (or
        // this waits for it), so the holder check below and its grant check never
        // both pass against the old state. See RoleRepository.lockAllByNameIn.
        roleRepository.lockAllByNameIn(List.of(normalize(name)));
        Role role = get(name);
        Set<String> granted = validatePermissions(permissions, role.getPermissions());

        // Refusing to strip the wildcard off SUPER_ADMIN is the same guard
        // UserAdminService applies to the account itself: SUPER_ADMIN is the
        // only role that can administer roles, so an admin who removes its
        // own roles:write locks every human out of the permission system with
        // no in-band way back — it would take a DB edit to recover.
        if (User.Role.SUPER_ADMIN.name().equals(role.getName())
                && !granted.contains(PermissionCatalog.WILDCARD)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPER_ADMIN must keep the '*' permission — narrowing it would lock the "
                            + "platform out of its own role administration.");
        }

        Set<String> previous = new LinkedHashSet<>(role.getPermissions());
        if (previous.equals(granted)) {
            log.info("setPermissions no-op role={} permissions={}", role.getName(), granted);
            return role;
        }

        Set<String> added = new LinkedHashSet<>(granted);
        added.removeAll(previous);
        Set<String> removed = new LinkedHashSet<>(previous);
        removed.removeAll(granted);
        if (!added.isEmpty()) {
            roleGrantGuard.requireMayGrant(roleGrantGuard.resolveCaller(adminEmail), added, role.getName());
        }
        // Adding a PLATFORM code (or making a business role a staff role) hands
        // every current holder platform authority: each must be staff-eligible
        // (400 staff_holders_ineligible, naming how many and why). An edit that
        // only REMOVES codes is never refused on these grounds.
        if (!added.isEmpty()
                && StaffEligibility.makesStaffAuthority(role.getName(), previous, granted, added)) {
            staffEligibility.requireHoldersEligible(role.getName(), adminEmail);
        }

        // Mutate in place: `permissions` is an @ElementCollection and Hibernate
        // tracks the instance it loaded, so swapping the reference would be lost.
        role.getPermissions().clear();
        role.getPermissions().addAll(granted);
        Role saved = roleRepository.save(role);
        // Flushed BEFORE the required audit, so any constraint failure surfaces
        // here — never after an audit row describing it has committed.
        roleRepository.flush();

        // A stale code (no longer in the catalog) classifies as PLATFORM, so
        // removing one bumps too — the answer that fails closed.
        boolean platformRemoved = removed.stream()
                .anyMatch(code -> PermissionCatalog.scopeOf(code) == PermissionCatalog.Scope.PLATFORM);
        int holdersSignedOut = platformRemoved ? tokenVersionBumper.bumpAllHolding(role.getName()) : 0;

        log.info("Role permissions changed name={} previous={} new={} holdersSignedOut={} by={}",
                role.getName(), previous, granted, holdersSignedOut, adminEmail == null ? "system" : adminEmail);
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("previousPermissions", previous.stream().sorted().toList());
        detail.put("newPermissions", granted.stream().sorted().toList());
        detail.put("platformPermissionRemoved", platformRemoved);
        detail.put("holdersSignedOut", holdersSignedOut);
        // Last statement: a change that cannot be recorded is not made.
        audit(AuditEventType.ROLE_PERMISSIONS_CHANGED, saved, adminEmail, auditContext, detail);
        return saved;
    }

    @Transactional
    public void delete(String name, String adminEmail, AuditContext auditContext) {
        Role role = get(name);

        if (role.isBuiltin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Built-in roles cannot be deleted. " + role.getName() + " is referenced by name "
                            + "in code (authorization checks, service-bundle mapping, the admin seed), "
                            + "so removing the row would break those silently rather than loudly. "
                            + "Remove its permissions instead if you want it to grant nothing.");
        }

        // A role still held by an account is not deletable. Deleting it would
        // leave those user rows naming a role that resolves to nothing, so each
        // holder would keep authenticating and silently lose every capability
        // the role carried — with no error anywhere to explain it.
        long holders = roleRepository.countUsersHolding(role.getName());
        if (holders > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Role " + role.getName() + " is still assigned to " + holders + " account(s). "
                            + "Reassign them with PUT /admin/users/{id}/roles before deleting it.");
        }

        roleRepository.delete(role);
        roleRepository.flush();
        log.info("Role deleted name={} by={}", role.getName(), adminEmail == null ? "system" : adminEmail);
        // Last statement: a deletion that cannot be recorded is not made.
        audit(AuditEventType.ROLE_DELETED, role, adminEmail, auditContext,
                Map.of("permissions", role.getPermissions().stream().sorted().toList()));
    }

    /**
     * Normalizes and checks every requested permission against the CODE catalog,
     * not the table.
     *
     * <p>The catalog is the set that is actually enforced; the table is a mirror
     * that can legitimately lag behind it (a permission dropped from the code
     * keeps its row — see {@code PermissionCatalogInitializer}). Validating
     * against the table would let an operator grant a code nothing checks any
     * more, producing a role that reads as capable and is not.
     *
     * <p>A code the role ALREADY holds is not re-validated against the catalog:
     * keeping a stale grant is harmless (the resolver drops it), and refusing it
     * would refuse an edit that only removes something — which must always be
     * possible (down to the last code; see the empty check).
     */
    private Set<String> validatePermissions(Collection<String> permissions, Collection<String> alreadyHeld) {
        Set<String> granted = new LinkedHashSet<>();
        if (permissions != null) {
            for (String permission : permissions) {
                if (permission != null && !permission.isBlank()) {
                    granted.add(permission.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (granted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "permissions must contain at least one permission. A role granting nothing is "
                            + "assignable but authorizes for nothing; list the available permissions "
                            + "with GET /admin/roles/permissions.");
        }

        Set<String> unknown = new LinkedHashSet<>();
        for (String permission : granted) {
            // A code reserved to the wildcard is left to RoleGrantGuard, which
            // refuses it as reserved whether or not this release defines it yet
            // (staff:* and organizations:manage are reserved before any endpoint
            // enforces them) — one answer for it, whoever asks.
            if (!PermissionCatalog.isKnown(permission) && !alreadyHeld.contains(permission)
                    && !PermissionCatalog.isReservedToWildcard(permission)) {
                unknown.add(permission);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown permission(s): " + String.join(", ", unknown)
                            + ". Permissions are defined in code, not created through the API — "
                            + "list what exists with GET /admin/roles/permissions.");
        }

        // The wildcard is SUPER_ADMIN's, seeded by V35. Letting it be granted
        // through the API would turn "create a role" into "create a superuser",
        // which is precisely the escalation UserAdminService refuses when it
        // blocks granting SUPER_ADMIN itself — the same hole by another door.
        if (granted.contains(PermissionCatalog.WILDCARD)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The '*' permission cannot be granted through this endpoint — it would make the "
                            + "role equivalent to SUPER_ADMIN. Grant the specific permissions the role "
                            + "needs instead.");
        }
        return granted;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * REQUIRED audit ({@link AuditService#recordRequired}): throws
     * {@code AuditUnavailableException} (503) when the row cannot be written, so
     * the role change rolls back with it. Always the caller's last statement.
     */
    private void audit(AuditEventType type, Role role, String adminEmail,
                       AuditContext auditContext, Map<String, Object> detail) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(detail);
        payload.put("role", role.getName());
        auditService.recordRequired(
                type,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                role.getName(), AuditService.TARGET_TYPE_ROLE,
                payload,
                auditContext == null ? AuditContext.none() : auditContext);
    }
}
