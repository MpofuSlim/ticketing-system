package com.innbucks.bookingservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A discount code an organizer created for one event (V27). The discount is
 * per eligible ticket, on top of its price tier; see {@code PromoCodes} for
 * how a booking applies it, under this row's lock.
 *
 * <p>{@link #categoryIds} is the one collection, LAZY like every association
 * here and read only inside a transaction; empty means every category.
 */
@Entity
@Table(name = "promo_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromoCode {

    public enum DiscountType { PERCENT, FIXED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    /** Upper case (CHECK chk_promo_codes_code_upper); matched case-insensitively by upper-casing the input. */
    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "max_uses_per_customer")
    private Integer maxUsesPerCustomer;

    /** UTC wall clock; null = open from the start. */
    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    /** UTC wall clock, exclusive; null = no end. */
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "promo_code_categories", joinColumns = @JoinColumn(name = "promo_code_id"))
    @Column(name = "category_id", nullable = false)
    @Builder.Default
    private Set<UUID> categoryIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Who created it: the organizer's uuid, or the admin's email (set by PromoCodeService). */
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
