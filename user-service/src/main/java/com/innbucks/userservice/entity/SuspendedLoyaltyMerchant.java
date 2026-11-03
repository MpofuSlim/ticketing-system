package com.innbucks.userservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A loyalty merchant whose business has its loyalty service suspended (V53).
 * Read from InnRewards when the service is suspended and kept here, so a
 * cashier's sign-in and a till key's exchange decide locally, with no call to
 * InnRewards. Removed on reinstate, and with the business.
 */
@Entity
@Table(name = "suspended_loyalty_merchants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuspendedLoyaltyMerchant {

    @Id
    @Column(name = "merchant_id", nullable = false, updatable = false)
    private UUID merchantId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "suspended_at", nullable = false, updatable = false)
    private LocalDateTime suspendedAt;
}
