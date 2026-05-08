package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

/**
 * Multi-tenant resolver. The API gateway forwards either an X-Tenant-Id (UUID)
 * or X-Tenant-Code header. Any request that hits a tenant-aware endpoint
 * without a valid tenant header is rejected.
 *
 * <p>Ownership is enforced once per request in {@link #requireTenant()}: the
 * authenticated principal must be the tenant's {@code ownerEmail}, unless they
 * hold the SUPER_ADMIN role (in which case they can act on any tenant). This
 * is the parent-child enforcement: a tenant owner can act across all merchants
 * under their tenant; SUPER_ADMIN can act across all tenants.
 */
@Component
@RequestScope
@Slf4j
public class TenantContext {

    private final TenantRepository tenants;
    private final HttpServletRequest request;
    private Tenant cached;

    public TenantContext(TenantRepository tenants, HttpServletRequest request) {
        this.tenants = tenants;
        this.request = request;
    }

    public Tenant requireTenant() {
        if (cached != null) return cached;
        Tenant resolved = resolveFromHeaders();
        verifyOwnership(resolved);
        cached = resolved;
        return cached;
    }

    public UUID requireTenantId() {
        return requireTenant().getId();
    }

    private Tenant resolveFromHeaders() {
        String idHeader = request.getHeader("X-Tenant-Id");
        if (idHeader != null && !idHeader.isBlank()) {
            try {
                return tenants.findById(UUID.fromString(idHeader.trim()))
                        .orElseThrow(() -> LoyaltyException.notFound("tenant"));
            } catch (IllegalArgumentException ex) {
                throw LoyaltyException.badRequest("BAD_TENANT", "X-Tenant-Id is not a valid UUID");
            }
        }
        String codeHeader = request.getHeader("X-Tenant-Code");
        if (codeHeader != null && !codeHeader.isBlank()) {
            java.util.List<Tenant> matches = tenants.findAllByCode(codeHeader.trim());
            if (matches.isEmpty()) throw LoyaltyException.notFound("tenant");
            if (matches.size() > 1) throw LoyaltyException.badRequest("AMBIGUOUS_TENANT",
                    "Multiple tenants share code '" + codeHeader.trim() + "'. Use X-Tenant-Id instead.");
            return matches.get(0);
        }
        throw LoyaltyException.badRequest("MISSING_TENANT", "X-Tenant-Id or X-Tenant-Code header is required");
    }

    private void verifyOwnership(Tenant tenant) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // Pre-auth code path (e.g. internal callers without a JWT). We
            // refuse rather than silently allow — every tenant-scoped call
            // must come through an authenticated principal.
            log.warn("Tenant access without authentication tenantId={}", tenant.getId());
            throw new AccessDeniedException("Authentication required to access tenant data");
        }
        // SUPER_ADMIN and MERCHANT_ADMIN both bypass ownership: SUPER_ADMIN
        // has platform-wide privileges, and MERCHANT_ADMIN is the role used
        // by the loyalty operator team to act across tenants. Per-tenant
        // isolation is therefore enforced only against EVENT_ORGANIZER and
        // CUSTOMER callers.
        if (hasRole(authentication, "ROLE_SUPER_ADMIN") || hasRole(authentication, "ROLE_MERCHANT_ADMIN")) {
            return;
        }
        String caller = authentication.getName();
        String owner = tenant.getOwnerEmail();
        if (owner == null || !owner.equals(caller)) {
            log.warn("Tenant ownership check failed tenantId={} caller={} ownerEmail={}",
                    tenant.getId(), caller, owner);
            throw new AccessDeniedException("You are not the owner of this tenant");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication.getAuthorities() == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
