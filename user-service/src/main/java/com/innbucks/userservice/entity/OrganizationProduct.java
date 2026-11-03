package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A product an organization has been GRANTED (V39). Pending asks stay in
 * {@code service_requests}; this row exists only once a product is decided.
 * {@code product} uses the bundle vocabulary of
 * {@link com.innbucks.userservice.service.Services}.
 */
@Entity
@Table(name = "organization_products")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationProduct {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 32, updatable = false)
    private String product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** V53: who suspended this service, when and why. Null while ACTIVE. */
    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspended_by", length = 254)
    private String suspendedBy;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;
}
