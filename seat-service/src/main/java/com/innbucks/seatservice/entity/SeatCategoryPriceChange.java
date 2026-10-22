package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One change to a category's price or price tiers (V11), written in the same
 * transaction as the change. Append-only: {@link Immutable} here, and a trigger
 * in Postgres refuses UPDATE and DELETE.
 *
 * <p>{@link #tiersBefore} / {@link #tiersAfter} are JSON snapshots of the tiers'
 * terms, so a tier renamed or deleted later still reads as it was.
 */
@Entity
@Immutable
@Table(name = "seat_category_price_changes")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatCategoryPriceChange {

    public enum ChangeType { CREATED, PRICE_CHANGED, TIERS_CHANGED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 20)
    private ChangeType changeType;

    @Column(name = "old_price", precision = 10, scale = 2, updatable = false)
    private BigDecimal oldPrice;

    @Column(name = "new_price", precision = 10, scale = 2, updatable = false)
    private BigDecimal newPrice;

    @Column(name = "tiers_before", updatable = false, columnDefinition = "TEXT")
    private String tiersBefore;

    @Column(name = "tiers_after", updatable = false, columnDefinition = "TEXT")
    private String tiersAfter;

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
