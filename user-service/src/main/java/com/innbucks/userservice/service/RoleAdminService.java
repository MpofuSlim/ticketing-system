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
 * <p>Every write here is audited: a role change is a change to who can do what,
 * which is exactly the class of event {@code audit_events} exists to make
 * tamper-evident. Granting yourself a permission and quietly ungranting it is
 * otherwise invisible.
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

    private final RoleRepository roleRepository;
    private final AuditService auditService;

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
        if (roleRepository.existsById(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A role named " + normalized + " already exists.");
        }

        Set<String> granted = validatePermissions(permissions);

        Role role = Role.builder()
                .name(normalized)
                .description(description == null ? "" : description.trim())
                .builtin(false)
                .createdBy(adminEmail)
                .permissions(granted)
                .build();
        Role saved = roleRepository.save(role);

        log.info("Role created name={} permissions={} by={}",
                normalized, granted, adminEmail == null ? "system" : adminEmail);
        audit(AuditEventType.ROLE_CREATED, saved, adminEmail, auditContext,
                Map.of("permissions", List.copyOf(granted)));
        return saved;
    }

    /**
     * Replace a role's permission set. Allowed on built-in roles too — editing
     * what {@code MERCHANT_ADMIN} can do is a legitimate and expected operation,
     * and it is only the NAME of a built-in that code depends on.
     */
    @Transactional
    public Role setPermissions(String name, Collection<String> permissions,
                               String adminEmail, AuditContext auditContext) {
        Role role = get(name);
        Set<String> granted = validatePermissions(permissions);

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

        // Mutate in place: `permissions` is an @ElementCollection and Hibernate
        // tracks the instance it loaded, so swapping the reference would be lost.
        role.getPermissions().clear();
        role.getPermissions().addAll(granted);
        Role saved = roleRepository.save(role);

        log.info("Role permissions changed name={} previous={} new={} by={}",
                role.getName(), previous, granted, adminEmail == null ? "system" : adminEmail);
        audit(AuditEventType.ROLE_PERMISSIONS_CHANGED, saved, adminEmail, auditContext,
                Map.of("previousPermissions", previous.stream().sorted().toList(),
                        "newPermissions", granted.stream().sorted().toList()));
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
        log.info("Role deleted name={} by={}", role.getName(), adminEmail == null ? "system" : adminEmail);
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
     */
    private Set<String> validatePermissions(Collection<String> permissions) {
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
                            + "with GET /admin/permissions.");
        }

        Set<String> unknown = new LinkedHashSet<>();
        for (String permission : granted) {
            if (!PermissionCatalog.isKnown(permission)) unknown.add(permission);
        }
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown permission(s): " + String.join(", ", unknown)
                            + ". Permissions are defined in code, not created through the API — "
                            + "list what exists with GET /admin/permissions.");
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

    private void audit(AuditEventType type, Role role, String adminEmail,
                       AuditContext auditContext, Map<String, Object> detail) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(detail);
        payload.put("role", role.getName());
        auditService.recordSuccess(
                type,
                adminEmail == null ? "system" : adminEmail,
                adminEmail == null ? AuditService.ACTOR_TYPE_SYSTEM : AuditService.ACTOR_TYPE_USER,
                role.getName(), AuditService.TARGET_TYPE_ROLE,
                payload,
                auditContext == null ? AuditContext.none() : auditContext);
    }
}
