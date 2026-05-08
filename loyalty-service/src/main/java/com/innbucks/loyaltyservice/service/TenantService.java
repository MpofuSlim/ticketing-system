package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Tenant;
import com.innbucks.loyaltyservice.entity.TenantMember;
import com.innbucks.loyaltyservice.exception.LoyaltyException;
import com.innbucks.loyaltyservice.repository.TenantMemberRepository;
import com.innbucks.loyaltyservice.repository.TenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenantService {

    private final TenantRepository tenants;
    private final TenantMemberRepository members;

    public TenantService(TenantRepository tenants, TenantMemberRepository members) {
        this.tenants = tenants;
        this.members = members;
    }

    public Dtos.TenantResponse create(Dtos.TenantRequest req) {
        return create(req, null);
    }

    /**
     * Creates a tenant. Tenants are now seed configuration created by
     * SUPER_ADMIN; merchant admins join existing tenants via
     * {@code POST /loyalty/tenants/{id}/join} rather than each minting their
     * own. The {@code ownerEmail} parameter is retained as audit metadata
     * (who provisioned this tenant) but no longer gates access — that's the
     * job of {@link TenantMember} rows.
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
     * Tenants the caller is a member of. Replaces the legacy "tenants I own"
     * lookup — a user can now belong to multiple tenants.
     */
    @Transactional(readOnly = true)
    public List<Dtos.TenantResponse> findMine(String email) {
        List<TenantMember> rows = members.findByEmail(email);
        return rows.stream()
                .map(m -> tenants.findById(m.getTenantId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(TenantService::toResponse)
                .toList();
    }

    public Dtos.TenantResponse suspend(UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.SUSPENDED);
        return toResponse(t);
    }

    public Dtos.TenantResponse activate(UUID id) {
        Tenant t = tenants.findById(id).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        t.setStatus(Tenant.Status.ACTIVE);
        return toResponse(t);
    }

    /**
     * Adds the caller as a member of the tenant. Idempotent — joining an
     * already-joined tenant returns the existing membership without error.
     */
    public Dtos.TenantMemberResponse addMember(UUID tenantId, String email) {
        Tenant t = tenants.findById(tenantId).orElseThrow(() -> LoyaltyException.notFound("tenant"));
        return members.findByTenantIdAndEmail(t.getId(), email)
                .map(TenantService::toMemberResponse)
                .orElseGet(() -> {
                    TenantMember m = new TenantMember();
                    m.setTenantId(t.getId());
                    m.setEmail(email);
                    members.save(m);
                    return toMemberResponse(m);
                });
    }

    @Transactional(readOnly = true)
    public List<Dtos.TenantMemberResponse> listMembers(UUID tenantId) {
        if (!tenants.existsById(tenantId)) throw LoyaltyException.notFound("tenant");
        return members.findByTenantId(tenantId).stream()
                .map(TenantService::toMemberResponse)
                .toList();
    }

    public void removeMember(UUID tenantId, String email) {
        if (!tenants.existsById(tenantId)) throw LoyaltyException.notFound("tenant");
        members.deleteByTenantIdAndEmail(tenantId, email);
    }

    public static Dtos.TenantResponse toResponse(Tenant t) {
        return new Dtos.TenantResponse(t.getId(), t.getCode(), t.getName(), t.getStatus().name());
    }

    public static Dtos.TenantMemberResponse toMemberResponse(TenantMember m) {
        return new Dtos.TenantMemberResponse(m.getId(), m.getTenantId(), m.getEmail(), m.getJoinedAt());
    }
}
