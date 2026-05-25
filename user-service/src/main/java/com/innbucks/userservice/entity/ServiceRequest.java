package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * An EVENT_ORGANIZER / MERCHANT_ADMIN's request to be granted access to an
 * additional default service bundle (e.g. "loyalty") on top of the bundles
 * picked at registration. SUPER_ADMIN reviews these and approves; on approval
 * the bundle is added to the requesting user's defaultServices set and the
 * matching role is granted.
 */
@Entity
@Table(name = "service_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Bundle name as defined in {@link com.innbucks.userservice.service.Services}, e.g. "loyalty". */
    @Column(nullable = false)
    private String service;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.UTC);

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** users.id of the SUPER_ADMIN who approved. Null while PENDING. */
    @Column(name = "reviewed_by")
    private Long reviewedBy;

    public enum Status {
        PENDING,
        APPROVED
    }
}
