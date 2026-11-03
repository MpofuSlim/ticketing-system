package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A seat category's number series (V34): "Bib T001, T002, …". Read-only to
 * JPA: every write is one native statement in {@code NumberSeriesRepository}
 * that guards itself (terms change only while nothing is assigned; the counter
 * only grows), so a stale entity can never write an old count back.
 */
@Entity
@Immutable
@Table(name = "category_number_series")
@Getter
public class NumberSeries {

    @Id
    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 20)
    private String label;

    @Column(nullable = false, length = 10)
    private String prefix;

    @Column(name = "start_value", nullable = false)
    private int startValue;

    @Column(nullable = false)
    private short padding;

    @Column(name = "assigned_count", nullable = false)
    private int assignedCount;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NumberSeries() {
    }
}
