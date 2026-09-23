package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A business, as a record of its own (V39).
 *
 * <p>Products attach to the organization rather than to one person: the
 * marketplace's seller, loyalty's merchants and ticketing's organizer each
 * point here. That is what lets a business use only the marketplace without
 * ever existing in loyalty, and lets several people work for one business.
 */
@Entity
@Table(name = "organizations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** The name people see. Never blank — registration and the backfill both
     *  fall back to the owner's own name. */
    @Column(nullable = false)
    private String name;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    private String address;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
