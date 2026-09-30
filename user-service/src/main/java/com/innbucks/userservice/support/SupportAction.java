package com.innbucks.userservice.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One support write, keyed by (agent, Idempotency-Key) — V45. A repeat of the
 * key returns the stored outcome instead of acting twice; see the migration for
 * how the unique index also serialises two concurrent repeats.
 */
@Entity
@Table(name = "support_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportAction {

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_user_uuid", nullable = false, updatable = false)
    private UUID agentUserUuid;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "lookup_id", nullable = false, length = 16, updatable = false)
    private String lookupId;

    @Column(name = "section", nullable = false, length = 32, updatable = false)
    private String section;

    @Column(name = "op", nullable = false, length = 64, updatable = false)
    private String op;

    @Column(name = "target", nullable = false, length = 128, updatable = false)
    private String target;

    @Column(name = "customer_key", length = 128, updatable = false)
    private String customerKey;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "what_happens_next", length = 1000)
    private String whatHappensNext;

    @Column(name = "case_id", length = 64, updatable = false)
    private String caseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
