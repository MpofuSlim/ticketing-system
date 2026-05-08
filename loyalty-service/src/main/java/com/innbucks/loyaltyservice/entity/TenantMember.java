package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Membership of a user in a tenant. Replaces the single-owner model that
 * stored an {@code ownerEmail} on the tenant: any number of merchant admins
 * can now belong to the same tenant, each granted access by an entry here.
 *
 * <p>{@code TenantContext} treats membership as the gate for tenant-scoped
 * actions (SUPER_ADMIN still bypasses). Members are added via
 * {@code POST /loyalty/tenants/{id}/join} and removed via
 * {@code DELETE /loyalty/tenants/{id}/members/me}.
 */
@Entity
@Table(name = "tenant_members", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tenant_member_tenant_email", columnNames = {"tenant_id", "email"})
}, indexes = {
        @Index(name = "idx_tenant_member_email", columnList = "email"),
        @Index(name = "idx_tenant_member_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TenantMember {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();
}
