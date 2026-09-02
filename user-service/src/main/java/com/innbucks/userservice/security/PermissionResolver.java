package com.innbucks.userservice.security;

import com.innbucks.userservice.entity.Role;
import com.innbucks.userservice.repository.RoleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Expands a user's role names into the flat permission set that goes into their
 * JWT.
 *
 * <p>Resolution happens at token-mint time (login and refresh), not per request:
 * the token then carries what the holder may do, so every other service can
 * authorize without a call back here, exactly as it already does for the
 * {@code roles} claim. The cost is the usual one for JWT authorization — a
 * permission change does not reach a live token. That is the same latency the
 * existing role model has, and the same lever fixes it:
 * {@code UserAdminService} already bumps {@code tokenVersion} on a role change to
 * force a re-mint fleet-wide.
 */
@Component
@Slf4j
public class PermissionResolver {

    private final RoleRepository roleRepository;

    public PermissionResolver(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    /**
     * The permissions granted by these role names, as the union across roles.
     *
     * <p>{@link PermissionCatalog#WILDCARD} held by any role expands to the whole
     * concrete catalog, so {@code SUPER_ADMIN} automatically picks up permissions
     * added by later releases. The wildcard itself is never emitted: the token
     * lists concrete codes only, so {@code hasAuthority} needs no wildcard-aware
     * matching and a decoded token is a readable statement of what it can do.
     */
    public Set<String> resolve(Collection<String> roleNames) {
        if (roleNames == null || roleNames.isEmpty()) return Set.of();

        List<Role> roles = roleRepository.findAllByNameIn(roleNames);

        if (roles.size() != roleNames.size()) {
            // A name on the user row that no longer resolves to a role — most
            // likely a role deleted out from under it. Not fatal: an unknown
            // name grants nothing, which is the safe direction. Logged because
            // it means a user row and the role table have drifted, and nothing
            // else would surface that.
            Set<String> found = new LinkedHashSet<>();
            roles.forEach(r -> found.add(r.getName()));
            Set<String> missing = new LinkedHashSet<>(roleNames);
            missing.removeAll(found);
            log.warn("Role name(s) on a user account resolve to no role, granting nothing: {}", missing);
        }

        Set<String> permissions = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role.getPermissions() == null) continue;
            if (role.getPermissions().contains(PermissionCatalog.WILDCARD)) {
                return PermissionCatalog.concrete();
            }
            permissions.addAll(role.getPermissions());
        }

        // A permission that was granted before it was removed from the code
        // catalog is dropped here rather than emitted. Emitting it would put a
        // code in the token that nothing enforces — harmless today, but it would
        // start granting access the moment someone reused the name for something
        // else.
        permissions.retainAll(PermissionCatalog.concrete());
        return permissions;
    }
}
