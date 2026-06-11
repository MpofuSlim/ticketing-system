package innbucks.paymentservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only journal of {@link Payment} state transitions. One row per
 * transition (the opening insert journals {@code null -> PENDING}); written
 * in the SAME transaction as the status change so ledger and journal cannot
 * diverge. Rows are never updated or deleted — this is what audits,
 * disputes, and incident reconstruction read from when the mutable
 * {@code payment.status} column only shows the end state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_event")
public class PaymentEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    /** Null for the opening transition (row creation). */
    @Column(name = "from_status", length = 32, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32, updatable = false)
    private String toStatus;

    /** Human-readable cause — upstream code/message, timeout note, etc. */
    @Column(name = "detail", length = 500, updatable = false)
    private String detail;

    /** Upstream (veengu) reference involved in this transition, if any. */
    @Column(name = "upstream_ref", length = 64, updatable = false)
    private String upstreamRef;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
