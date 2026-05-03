package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One row per tenant. Configures how many points are credited per dollar
// of cash spent (earnRate) and how many points are needed to offset a dollar
// at redemption time (redeemRate). Tenants without a configured rule fall
// back to app.loyalty.default-* in application.yaml.
@Entity
@Table(name = "loyalty_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    @Column(name = "earn_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal earnRate;

    @Column(name = "redeem_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal redeemRate;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
