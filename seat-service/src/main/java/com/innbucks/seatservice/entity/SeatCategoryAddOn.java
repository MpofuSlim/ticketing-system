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
 * An extra a buyer can add to each ticket of a category (V12), e.g. "Parking
 * pass" 5.00 on VIP: at most one per ticket, and at most {@link #maxQuantity}
 * in all when set (never more than the category's tickets). booking-service
 * counts what has sold under the category's stock lock and keeps each sold
 * add-on's name and price, so nothing here restates a sale.
 *
 * <p>{@link #categoryId} is a plain column, like the price tiers', so nothing
 * here can lazy-load a category.
 */
@Entity
@Table(name = "seat_category_add_ons")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatCategoryAddOn {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    /** 0-based display order. */
    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** How many may sell in all; null = one per ticket and no other limit. */
    @Column(name = "max_quantity")
    private Integer maxQuantity;

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
