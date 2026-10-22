package com.innbucks.seatservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One phase of a seat category's pricing (V9): sold at {@link #price} until
 * {@link #endsAt}, or until {@link #quantity} tickets are sold at it, whichever
 * comes first. Tiers apply in {@link #position} order and the first one still
 * open is the price; after them all, the category's own price. Which tier is
 * open is never stored: see {@link com.innbucks.seatservice.service.CategoryPricing}.
 *
 * <p>{@link #categoryId} is a plain column, not an association, so nothing here
 * can lazy-load a category (or its seats) by accident.
 */
@Entity
@Table(name = "seat_category_price_tiers")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatCategoryPriceTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    /** 0-based order: the first open tier is the price. */
    @Column(name = "tier_order", nullable = false)
    private int position;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** UTC wall clock at which the tier closes (exclusive); null closes on quantity alone. */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    /** Tickets that may sell at this tier; null closes on time alone. */
    private Integer quantity;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
