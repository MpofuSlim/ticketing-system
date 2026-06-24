package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Per-organizer override of the deployment-default platform billing terms.
 *
 * <p>An organizer without a row here is billed at the deployment defaults
 * ({@code app.invoicing.default-commission-rate} /
 * {@code .default-billing-cycle}); a row lets the platform negotiate a
 * bespoke commission rate or cycle for a specific organizer. The PK is the
 * organizer's stable {@code user_uuid} (== {@code Booking.tenantUserUuid} ==
 * the JWT {@code organizerUuid} claim).
 *
 * <p>The rate stored here is the <em>current</em> rate; each generated invoice
 * snapshots the rate it was billed at, so changing this never rewrites issued
 * invoices.
 */
@Entity
@Table(name = "organizer_billing_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrganizerBillingConfig {

    @Id
    @Column(name = "organizer_uuid")
    private UUID organizerUuid;

    /** Commission as a percentage of net confirmed ticket revenue (0..100; e.g. 10.0000 = 10%). */
    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** How often the platform closes a period and bills the organizer. */
    public enum BillingCycle {
        WEEKLY,
        MONTHLY
    }
}
