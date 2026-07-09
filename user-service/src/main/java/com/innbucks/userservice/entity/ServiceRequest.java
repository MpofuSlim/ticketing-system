package com.innbucks.userservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
@EntityListeners(AuditingEntityListener.class)
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

    private LocalDateTime updatedAt;

    /** Acting principal (user_uuid or JWT email) that created this request;
     *  null for unauthenticated writes. Auto-stamped by JPA auditing. */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    /** Acting principal on the last update (e.g. the SUPER_ADMIN who reviewed it). */
    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PreUpdate
    void stampUpdatedAt() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public enum Status {
        PENDING,
        APPROVED
    }
}
