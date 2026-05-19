package com.innbucks.userservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.repository.AuditEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * Writes append-only rows to the {@code audit_events} table for
 * security-sensitive actions (auth events, admin operations,
 * password changes, etc.).
 *
 * <h2>Transactional isolation</h2>
 * Every {@code record(...)} call runs in a REQUIRES_NEW transaction
 * managed by {@link TransactionTemplate}. Two properties follow:
 * <ul>
 *   <li><b>Survives caller rollback</b> — a /auth/login that throws
 *       and rolls back STILL persists the audit row, so an attacker
 *       can't suppress their own audit trail by crashing the
 *       request mid-flight.</li>
 *   <li><b>Doesn't fail caller</b> — exceptions from the audit
 *       write are caught and logged inside {@code record(...)} so
 *       a transient DB hiccup on the audit path doesn't bubble up
 *       and reject an otherwise-valid login.</li>
 * </ul>
 *
 * <p>This is a deliberate trade-off: an audit gap is preferable to
 * a hard outage. Operators reading the application logs will see
 * the {@code AUDIT_WRITE_FAILED} signal and can reconcile from
 * other sources (gateway access log, OTel spans).
 *
 * <h2>Sensitive data</h2>
 * Callers MUST NOT pass passwords, raw tokens, OTP codes, or PIN
 * material in {@code metadata}. Identifiers (email / msisdn) are
 * acceptable because they're already in {@code users} alongside
 * the {@code actor_id} this row points at. Phone numbers in
 * metadata should use the last-4 masking the rest of the codebase
 * uses for log lines.
 */
@Service
@Slf4j
public class AuditService {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    public static final String ACTOR_TYPE_USER = "USER";
    public static final String ACTOR_TYPE_ANONYMOUS = "ANONYMOUS";
    public static final String ACTOR_TYPE_SYSTEM = "SYSTEM";

    public static final String TARGET_TYPE_USER = "USER";

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AuditService(AuditEventRepository repository,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persist one audit row. Any exception during the write is
     * caught, logged, and swallowed — the caller's flow is never
     * interrupted by an audit failure.
     *
     * <p>{@code context} carries the network-side fields (ip,
     * userAgent); pass {@link AuditContext#none()} when the call
     * site doesn't have an HTTP request handy (background jobs).
     */
    public void record(AuditEventType type,
                       String outcome,
                       String actorId,
                       String actorType,
                       String targetId,
                       String targetType,
                       String failureReason,
                       Map<String, Object> metadata,
                       AuditContext context) {
        AuditEvent event = AuditEvent.builder()
                .occurredAt(Instant.now())
                .eventType(type.name())
                .outcome(outcome)
                .actorId(actorId)
                .actorType(actorType)
                .targetId(targetId)
                .targetType(targetType)
                .ipAddress(context == null ? null : context.ipAddress())
                .userAgent(truncate(context == null ? null : context.userAgent(), 512))
                .failureReason(truncate(failureReason, 255))
                .correlationId(MDC.get("correlationId"))
                .metadata(serialiseMetadata(metadata))
                .build();
        try {
            transactionTemplate.execute(status -> repository.save(event));
        } catch (RuntimeException ex) {
            // Don't propagate: a broken audit path must not break login.
            // Operators reading logs will see this marker and can pivot
            // to gateway access logs / OTel for reconstruction.
            log.error("AUDIT_WRITE_FAILED type={} outcome={} actorId={} reason={}",
                    type.name(), outcome, actorId, ex.getMessage(), ex);
        }
    }

    /** Convenience overload for success rows that need no failure_reason. */
    public void recordSuccess(AuditEventType type,
                              String actorId,
                              String actorType,
                              String targetId,
                              String targetType,
                              Map<String, Object> metadata,
                              AuditContext context) {
        record(type, OUTCOME_SUCCESS, actorId, actorType, targetId, targetType,
                null, metadata, context);
    }

    /** Convenience overload for failure rows that need a reason. */
    public void recordFailure(AuditEventType type,
                              String actorId,
                              String actorType,
                              String targetId,
                              String targetType,
                              String failureReason,
                              Map<String, Object> metadata,
                              AuditContext context) {
        record(type, OUTCOME_FAILURE, actorId, actorType, targetId, targetType,
                failureReason, metadata, context);
    }

    private String serialiseMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            // A serialisation failure shouldn't drop the row entirely —
            // record a marker so the rest of the audit data still
            // lands and operators know to investigate.
            log.warn("AUDIT_METADATA_SERIALISATION_FAILED keys={} reason={}",
                    metadata.keySet(), ex.getMessage());
            return "{\"_error\":\"metadata-serialisation-failed\"}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }
}
