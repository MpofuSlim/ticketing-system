package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "wallets", indexes = {
        @Index(name = "idx_wallet_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80)
    private String label = "Main";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type = Type.MAIN;

    /**
     * Optional pocket label, e.g. "FUEL", "PROMO". When set, only matching
     * rewards may be credited here and only matching redemptions may debit.
     */
    @Column(length = 40)
    private String pocket;

    @Version
    private long version;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "locked_until")
    private LocalDate lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum Type { MAIN, SUB, SAVINGS }
}
