package com.innbucks.eventservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One change to an event's ticket sales window (V22): its sales start, its
 * sales end, or both. Written in the same transaction as the change.
 * Append-only: {@link Immutable} here, and a trigger in Postgres refuses UPDATE
 * and DELETE. Times are UTC wall clocks, as stored on the event; null = no
 * schedule on that side.
 */
@Entity
@Immutable
@Table(name = "event_sales_window_changes")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSalesWindowChange {

    /** CREATED: the event was created with a sales start or end. CHANGED: an update moved either. */
    public enum ChangeType { CREATED, CHANGED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "old_sales_start_at", updatable = false)
    private LocalDateTime oldSalesStartAt;

    @Column(name = "new_sales_start_at", updatable = false)
    private LocalDateTime newSalesStartAt;

    @Column(name = "old_sales_end_at", updatable = false)
    private LocalDateTime oldSalesEndAt;

    @Column(name = "new_sales_end_at", updatable = false)
    private LocalDateTime newSalesEndAt;

    @CreatedBy
    @Column(name = "changed_by", updatable = false, length = 255)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void onCreate() {
        if (changedAt == null) {
            changedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
