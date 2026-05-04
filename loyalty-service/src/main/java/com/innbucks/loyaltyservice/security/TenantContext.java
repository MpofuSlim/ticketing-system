package com.innbucks.loyaltyservice.security;

import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

/**
 * Multi-tenant resolver. The API gateway forwards either an X-Tenant-Id (UUID)
 * or X-Tenant-Code header. Any request that hits a tenant-aware endpoint
 * without a valid tenant header is rejected.
 */
@Component
@RequestScope
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
        String idHeader = request.getHeader("X-Tenant-Id");
        if (idHeader != null && !idHeader.isBlank()) {
            try {
                cached = tenants.findById(UUID.fromString(idHeader.trim()))
                        .orElseThrow(() -> LoyaltyException.notFound("tenant"));
                return cached;
            } catch (IllegalArgumentException ex) {
                throw LoyaltyException.badRequest("BAD_TENANT", "X-Tenant-Id is not a valid UUID");
            }
        }
        String codeHeader = request.getHeader("X-Tenant-Code");
        if (codeHeader != null && !codeHeader.isBlank()) {
            cached = tenants.findByCode(codeHeader.trim())
                    .orElseThrow(() -> LoyaltyException.notFound("tenant"));
            return cached;
        }
        throw LoyaltyException.badRequest("MISSING_TENANT", "X-Tenant-Id or X-Tenant-Code header is required");
    }

    public UUID requireTenantId() {
        return requireTenant().getId();
    }
}
