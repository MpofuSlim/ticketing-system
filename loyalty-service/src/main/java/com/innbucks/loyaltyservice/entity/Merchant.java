package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants", indexes = {
        @Index(name = "idx_merchant_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Merchant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 80)
    private String category;

    @Column(length = 200)
    private String location;

    @Column(length = 8)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    @Column(name = "fee_per_point_issued", precision = 19, scale = 6)
    private BigDecimal feePerPointIssued = BigDecimal.ZERO;

    @Column(name = "fee_per_voucher_issued", precision = 19, scale = 6)
    private BigDecimal feePerVoucherIssued = BigDecimal.ZERO;

    @Column(name = "fee_per_voucher_redeemed", precision = 19, scale = 6)
    private BigDecimal feePerVoucherRedeemed = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    // Email of the user-service identity that admins this merchant. Stamped
    // at create time from the JWT subject so AuthService can resolve a
    // MERCHANT_ADMIN's merchantId at login without manual binding.
    @Column(name = "admin_email", length = 255)
    private String adminEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum BillingCycle { WEEKLY, MONTHLY }
    public enum Status { ACTIVE, INACTIVE }
}
