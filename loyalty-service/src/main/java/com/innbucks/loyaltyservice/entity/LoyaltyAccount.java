package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One wallet per (customer, tenant). Balance is the running points total —
// updated by EARN/REDEEM transactions. Optimistic-locked so concurrent
// redeem/earn calls for the same account can't silently overwrite each other.
@Entity
@Table(
        name = "loyalty_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_loyalty_accounts_customer_tenant",
                columnNames = {"customer_id", "tenant_id"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    private Long version;

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
