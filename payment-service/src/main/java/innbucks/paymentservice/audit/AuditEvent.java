package innbucks.paymentservice.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only row in the {@code audit_events} table. Writes go
 * through {@code AuditService.record(...)} which manages a
 * REQUIRES_NEW transaction so audit failures don't break the
 * caller's flow and audit successes survive caller-side rollback.
 *
 * <p>Every field is non-functional from the application's point of
 * view — the rows exist purely for forensics, compliance reporting,
 * and incident response. The application never reads from this
 * table on the hot path.
 */
@Entity
@Table(name = "audit_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "actor_type", length = 32)
    private String actorType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /**
     * OWASP A09 tamper-evidence: HMAC-SHA256 over the immutable row fields
     * (see {@code AuditService.computeHmac}), keyed by {@code audit.hmac-secret}.
     * Lets {@code AuditIntegrityVerifier} detect any post-hoc modification of a
     * row by an attacker with DB write access. Null on rows written before V29.
     */
    @Column(name = "row_hmac", length = 64)
    private String rowHmac;

    /**
     * OWASP A09 hash-chaining: {@code HMAC-SHA256(key, prev_chain_hmac || row_hmac)}
     * binding this row to its predecessor (see {@code AuditService.computeChainHmac}).
     * Where {@link #rowHmac} proves the row's content is intact, this proves no
     * row was DELETED, REORDERED, or truncated from the tail — deleting a row
     * breaks the link at the next surviving row, and the attacker can't repair
     * the downstream chain without the key. Walked by {@code AuditIntegrityVerifier};
     * null on rows written before V10 (chain not applicable, reported as legacy).
     */
    @Column(name = "chain_hmac", length = 64)
    private String chainHmac;
}
