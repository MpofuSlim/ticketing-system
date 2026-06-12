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

import java.time.Instant;
import java.util.UUID;

/**
 * One row per settlement-reconciliation run — the nightly (or manually
 * triggered) match of OUR money-received ledger rows against InnBucks' own
 * code mini-statement. This is the report a bank's back office reads every
 * morning: persisted so discrepancies are auditable and queryable, not just
 * log lines that scrolled away.
 *
 * <p>Discrepancy buckets:
 * <ul>
 *   <li><b>oursNotTheirs</b> — we recorded money received, InnBucks' statement
 *       has no finalised code for it. The worst class of mismatch: our ledger
 *       may be lying. Investigate immediately.</li>
 *   <li><b>theirsNotOurs</b> — InnBucks shows a finalised (paid) code we have
 *       not booked as money (row missing, or stuck TOKEN_ISSUED/EXPIRED).
 *       A customer paid and got nothing — the make-it-right queue.</li>
 *   <li><b>amountMismatches</b> — matched code, different amount. Should be
 *       impossible given the generation-time echo check; non-zero means the
 *       cents contract drifted.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "recon_run")
public class ReconRun {

    public enum Status {
        /** Every money row matched a finalised statement code. */
        CLEAN,
        /** At least one discrepancy bucket is non-zero — operator must look. */
        DISCREPANT,
        /** The statement could not be fetched; nothing was compared. */
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    /** What we matched against — MINI_STATEMENT today; FULL_STATEMENT later. */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    /**
     * False when the statement's oldest entry is YOUNGER than the window
     * start — the (recency-capped) mini statement may not reach back far
     * enough, so absence-of-evidence is weak and the run's oursNotTheirs
     * findings need manual confirmation.
     */
    @Column(name = "coverage_complete", nullable = false)
    private boolean coverageComplete;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "matched_amount_cents", nullable = false)
    private long matchedAmountCents;

    @Column(name = "ours_not_theirs", nullable = false)
    private int oursNotTheirs;

    @Column(name = "theirs_not_ours", nullable = false)
    private int theirsNotOurs;

    @Column(name = "amount_mismatches", nullable = false)
    private int amountMismatches;

    /** Human-readable discrepancy lines (capped); empty on CLEAN runs. */
    @Column(name = "discrepancy_detail", length = 4000)
    private String discrepancyDetail;

    /** Fetch/compare failure reason on FAILED runs. */
    @Column(name = "error", length = 1000)
    private String error;

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
