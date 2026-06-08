package innbucks.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Local ledger row for an InnBucks/veengu-backed ticketing payment. ONE row
 * per attempted payment — inserted PENDING before the call to
 * innbucks-core-gateway, then UPDATEd to SUCCEEDED or FAILED based on the
 * veengu verdict. The two writes happen in separate transactions
 * (Propagation.REQUIRES_NEW on PaymentRecordService methods) so a successful
 * veengu debit followed by a local DB blip leaves a PENDING row in the
 * ledger for reconciliation to investigate, rather than the row vanishing
 * silently.
 *
 * <p>Deliberately separate from the existing {@link Transaction} table:
 * {@code transactions} is the customer-initiated wallet transfer / withdrawal
 * ledger (different semantics, different velocity caps, different state
 * machine on top in {@code TransactionService}). {@code payment} is the
 * checkout-time debit for a booking — narrower domain, simpler shape.
 *
 * <p>Conventions mirror {@link Transaction}:
 * <ul>
 *   <li>Client-assigned UUID id (so we can log it before commit).</li>
 *   <li>{@code Instant} timestamps (always UTC; aligns with TIMESTAMPTZ).</li>
 *   <li>{@code @Enumerated(STRING)} for the status enum.</li>
 *   <li>{@code created_at} not updatable; {@code completed_at} set on the
 *       terminal transition.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Caller-assigned reference passed to veengu as its {@code reference}
     * field — the idempotency key on the upstream side. We generate it
     * locally with a {@code TKT-PMT-<uuid>} prefix so support can spot
     * ticketing rows in veengu's logs at a glance.
     */
    @Column(name = "payment_reference", nullable = false, unique = true, length = 64)
    private String paymentReference;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "customer_msisdn", nullable = false, length = 32)
    private String customerMsisdn;

    @Column(name = "customer_account", nullable = false, length = 64)
    private String customerAccount;

    @Column(name = "merchant_account", nullable = false, length = 64)
    private String merchantAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    /** Veengu's internal transaction id, populated on terminal SUCCEEDED. */
    @Column(name = "veengu_transaction_id", length = 64)
    private String veenguTransactionId;

    /** Veengu's error code (e.g. NOT_SUFFICIENT_FUNDS), populated on FAILED. */
    @Column(name = "upstream_error_code", length = 64)
    private String upstreamErrorCode;

    @Column(name = "upstream_error_message", length = 500)
    private String upstreamErrorMessage;

    /** Idempotency key supplied by the caller via the Idempotency-Key header. */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Booking-service's confirmation number, populated after the post-debit confirm. */
    @Column(name = "confirmation_number", length = 64)
    private String confirmationNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }

    public enum PaymentStatus {
        PENDING, SUCCEEDED, FAILED
    }
}
