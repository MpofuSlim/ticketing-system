package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One event a sales agent sells for (V21), with the organizer's terms for it:
 * an optional ticket cap and a commission percent on what the buyer paid.
 * Ids only, no associations, like {@link EventScannerAssignment}.
 *
 * <p>booking-service reads these terms live on every sale through
 * {@code GET /events/internal/seller-access}; nothing here counts sales.
 */
@Entity
@Table(name = "event_sales_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSalesAssignment {

    @Id
    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "scanner_id", nullable = false, updatable = false)
    private UUID scannerId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** Most tickets this agent may have sold for the event at once; null = no cap. */
    @Column(name = "ticket_cap")
    private Integer ticketCap;

    /** Percent of what the buyer paid, 0 to 100, two decimals. */
    @Column(name = "commission_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionPercent;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    @Column(name = "assigned_by", updatable = false)
    private String assignedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (assignedAt == null) assignedAt = now;
        if (commissionPercent == null) commissionPercent = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
