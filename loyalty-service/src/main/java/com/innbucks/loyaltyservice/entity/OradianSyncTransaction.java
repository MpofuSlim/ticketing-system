package com.innbucks.loyaltyservice.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-operation record of an attempt to sync a wallet mutation to the
 * customer's LPW account on Oradian.
 *
 * <p>Note the distinction from the existing {@link LoyaltyTransaction}:
 * <ul>
 *   <li>{@code LoyaltyTransaction} = upstream business event (PURCHASE,
 *       MANUAL_GRANT, REVERSAL, ...). One row per earn/spend trigger.</li>
 *   <li>{@code OradianSyncTransaction} (this) = the Oradian-side mirror
 *       attempt for that event, with PENDING / SUCCEEDED / FAILED
 *       lifecycle and Oradian-assigned IDs.</li>
 *   <li>{@link PointsLedger} = the LOCAL atomic balance delta written on
 *       a successful sync, with balance_after for audit replay.</li>
 * </ul>
 *
 * The three tables join on {@link #sourceTransactionId} for end-to-end
 * forensic traces of any earn or spend.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@code PENDING} — inserted just before the middleware call.</li>
 *   <li>{@code SUCCEEDED} — flipped after the middleware returns 200, in
 *       the same DB transaction as the wallet.balance update + the
 *       PointsLedger insert. Oradian-assigned IDs captured on the row.</li>
 *   <li>{@code FAILED} — flipped on upstream rejection / unreachable /
 *       circuit-open. Wallet is NOT mutated, no PointsLedger row.</li>
 * </ol>
 *
 * <p>The reconciliation job scans {@code PENDING} rows older than the
 * configured grace window and finalises them by polling
 * {@code GET /internal/deposits/{accountId}}. The middleware's
 * idempotency-key replay surfaces the original outcome — so even if the
 * HTTP call died mid-flight, the row converges to the correct terminal
 * state.
 */
@Entity
@Table(name = "oradian_sync_transactions")
@Getter
@Setter
@NoArgsConstructor
public class OradianSyncTransaction {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /**
     * Signed delta in points. Positive = earn / Oradian credit;
     * negative = spend / Oradian withdraw. Mirrors {@link PointsLedger}
     * {@code delta} semantics; same value lands in both tables on
     * success.
     */
    @Column(name = "delta_points", nullable = false, precision = 19, scale = 4)
    private BigDecimal deltaPoints;

    /**
     * Free-form reason aligned with {@link PointsLedger#getReason()}
     * ({@code "earn:PURCHASE"}, {@code "redeem:VOUCHER"},
     * {@code "transfer-in"}, ...). Same string in both tables so an
     * ops join shows one row per side of the operation.
     */
    @Column(nullable = false, length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /**
     * Oradian external account ID we're crediting / withdrawing
     * against. Captured at insertion time, not lazily — we don't
     * attempt an upstream call without knowing the target account.
     */
    @Column(name = "oradian_account_id", nullable = false, length = 64)
    private String oradianAccountId;

    @Column(name = "oradian_transaction_id", length = 64)
    private String oradianTransactionId;

    @Column(name = "oradian_command_id", length = 64)
    private String oradianCommandId;

    @Column(name = "oradian_reference_number", length = 64)
    private String oradianReferenceNumber;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    /**
     * Optional pointer to the upstream {@link LoyaltyTransaction} or
     * other business-event row that triggered this sync. Mirrors
     * {@link PointsLedger#getTransactionId()} so the same upstream
     * event groups across all three ledgers.
     */
    @Column(name = "source_transaction_id")
    private UUID sourceTransactionId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
