package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import com.innbucks.loyaltyservice.service.TenantCachedLookup;
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
 * <p>Membership is enforced once per request in {@link #requireTenant()}: the
 * authenticated principal must be a member of the tenant (row in
 * {@code tenant_members}), unless they hold the SUPER_ADMIN role — SUPER_ADMIN
 * acts across every tenant on the platform without needing membership rows.
 *
 * <p>Members are added via {@code POST /loyalty/tenants/{id}/join} and removed
 * via {@code DELETE /loyalty/tenants/{id}/members/me}. This replaces the older
 * single-owner model where {@code tenant.ownerEmail} gated access.
 */
@Component
@RequestScope
@Slf4j
public class TenantContext {

    private final TenantRepository tenants;
    private final TenantCachedLookup lookup;
    private final HttpServletRequest request;
    private Tenant cached;

    public TenantContext(TenantRepository tenants, TenantCachedLookup lookup, HttpServletRequest request) {
        this.tenants = tenants;
        this.lookup = lookup;
        this.request = request;
    }

    public Tenant requireTenant() {
        if (cached != null) return cached;
        Tenant resolved = resolveFromHeaders();
        verifyMembership(resolved);
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
                // Cached lookup — backed by Caffeine via TenantCachedLookup.
                return lookup.findById(UUID.fromString(idHeader.trim()))
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
        // Log the method + URI + caller so a "MISSING_TENANT" in prod tells you
        // exactly which frontend call dropped the header instead of leaving you
        // to guess from the error message alone.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String caller = (auth != null && auth.getName() != null) ? auth.getName() : "anonymous";
        log.warn("MISSING_TENANT method={} uri={} caller={} origin={}",
                request.getMethod(), request.getRequestURI(), caller, request.getHeader("Origin"));
        throw LoyaltyException.badRequest("MISSING_TENANT", "X-Tenant-Id or X-Tenant-Code header is required");
    }

    private void verifyMembership(Tenant tenant) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // Pre-auth code path (e.g. internal callers without a JWT). We
            // refuse rather than silently allow — every tenant-scoped call
            // must come through an authenticated principal.
            log.warn("Tenant access without authentication tenantId={}", tenant.getId());
            throw new AccessDeniedException("Authentication required to access tenant data");
        }
        if (hasRole(authentication, "ROLE_SUPER_ADMIN")) {
            return;
        }
        String caller = authentication.getName();
        if (!lookup.isMember(tenant.getId(), caller)) {
            log.warn("Tenant membership check failed tenantId={} caller={}",
                    tenant.getId(), caller);
            throw new AccessDeniedException(
                    "You are not a member of this tenant. Call POST /loyalty/tenants/{id}/join first.");
        }
    }

    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication.getAuthorities() == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
