package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "voucher_templates", indexes = {
        @Index(name = "idx_voucher_tpl_tenant", columnList = "tenant_id")
})
@Getter
@Setter
@NoArgsConstructor
public class VoucherTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherType type = VoucherType.SINGLE_USE;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private ValueType valueType = ValueType.AMOUNT;

    @Column(name = "face_value", precision = 19, scale = 4)
    private BigDecimal value;

    @Column(length = 8)
    private String currency = "USD";

    @Column(name = "free_item_sku", length = 80)
    private String freeItemSku;

    @Column(name = "usage_limit", nullable = false)
    private int usageLimit = 1;

    @Column(name = "validity_days")
    private Integer validityDays;

    // Stored as a comma-separated list of shop UUIDs in VARCHAR(1000) via
    // UuidListConverter — kept that way so we don't need a schema migration.
    // Null = redeemable at every shop under the merchant/tenant.
    @Convert(converter = UuidListConverter.class)
    @Column(name = "applicable_outlets", length = 1000)
    private List<UUID> applicableOutlets;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum VoucherType { SINGLE_USE, MULTI_USE, CAMPAIGN, REFERRAL, CORPORATE }
    public enum ValueType { AMOUNT, PERCENT, FREE_ITEM, COMBO }
}
