package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public Dtos.TenantResponse create(Dtos.TenantRequest req) {
        return create(req, null);
    }

    /**
     * Creates a tenant and stamps {@code ownerEmail} so subsequent calls that
     * pass this tenant's UUID via X-Tenant-Id can be ownership-checked. Pass
     * {@code null} as the owner only for SUPER_ADMIN-driven seed data — every
     * tenant created from a normal request should have an owner.
     */
    public Dtos.TenantResponse create(Dtos.TenantRequest req, String ownerEmail) {
        Tenant t = new Tenant();
        t.setCode(req.code());
        t.setName(req.name());
        t.setOwnerEmail(ownerEmail);
        tenants.save(t);
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public List<Dtos.TenantResponse> list() {
        return tenants.findAll().stream().map(TenantService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Page<Dtos.TenantResponse> list(Pageable pageable) {
        return tenants.findAll(pageable).map(TenantService::toResponse);
    }

    /**
     * Paginated list scoped to the caller. SUPER_ADMIN sees every tenant on the
     * platform; everyone else sees only the tenants they own. Lets the same
     * endpoint serve both an operator dashboard and a merchant's own tenant
     * picker without leaking tenants across owners.
     */
    @Transactional(readOnly = true)
    public Page<Dtos.TenantResponse> listForCaller(Pageable pageable,
                                                   String callerEmail,
                                                   boolean isSuperAdmin) {
        if (isSuperAdmin) {
            return tenants.findAll(pageable).map(TenantService::toResponse);
        }
        return tenants.findAllByOwnerEmail(callerEmail, pageable)
                .map(TenantService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<Dtos.TenantResponse> findMine(String ownerEmail) {
        return tenants.findAllByOwnerEmail(ownerEmail).stream()
                .map(TenantService::toResponse)
                .toList();
    }

    public Dtos.TenantResponse suspend(java.util.UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.SUSPENDED);
        return toResponse(t);
    }

    public Dtos.TenantResponse activate(java.util.UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.ACTIVE);
        return toResponse(t);
    }

    public static Dtos.TenantResponse toResponse(Tenant t) {
        return new Dtos.TenantResponse(t.getId(), t.getCode(), t.getName(), t.getStatus().name());
    }
}
