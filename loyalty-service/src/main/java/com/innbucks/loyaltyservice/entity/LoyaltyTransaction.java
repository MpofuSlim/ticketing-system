package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Append-only ledger. `reference` is the caller-supplied idempotency key
// (typically the bookingId) — a unique constraint per (account, type,
// reference) pair stops a retried Feign call from double-crediting.
@Entity
@Table(
        name = "loyalty_transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_loyalty_tx_account_type_reference",
                columnNames = {"account_id", "type", "reference"}
        )
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyTransaction {

    public enum Type { EARN, REDEEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    // Always positive; sign is implied by `type`.
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal points;

    // For EARN, this is the cash amount that produced the points. Null for
    // REDEEM (the dollar value of redeemed points lives in the calling
    // booking, not here).
    @Column(name = "dollar_amount", precision = 18, scale = 4)
    private BigDecimal dollarAmount;

    @Column(nullable = false)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
