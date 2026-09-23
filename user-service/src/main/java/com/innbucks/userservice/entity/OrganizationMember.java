package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A person's place in one organization (V39). The role is scoped to that
 * business only: the same person can be OWNER of one organization and STAFF
 * of another.
 */
@Entity
@Table(name = "organization_members")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizationMember {

    /**
     * Ordered by authority. OWNER may do everything, including handing out
     * OWNER; ADMIN runs the business day to day and manages STAFF; STAFF works
     * in it.
     */
    public enum Role { OWNER, ADMIN, STAFF }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);
}
