package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One event a super-app scanner may scan (V14). Ids only, no associations:
 * every read that needs the event or the scanner says so in its query.
 */
@Entity
@Table(name = "event_scanner_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventScannerAssignment {

    @Id
    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "scanner_id", nullable = false, updatable = false)
    private UUID scannerId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    @Column(name = "assigned_by", updatable = false)
    private String assignedBy;

    @PrePersist
    void onCreate() {
        if (assignedAt == null) assignedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
