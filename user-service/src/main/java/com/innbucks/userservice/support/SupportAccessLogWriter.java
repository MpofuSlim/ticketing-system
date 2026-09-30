package com.innbucks.userservice.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.exception.SupportPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@link SupportAccessLog} rows, each in its OWN transaction
 * ({@code REQUIRES_NEW}) so a row commits whatever the calling request does
 * next — a refusal's row survives the exception that refuses it.
 *
 * <p>Two strengths, and the rule for choosing: <b>customer data leaving the
 * service is logged fail-CLOSED</b> ({@link #required}: a search or detail read
 * whose row cannot be written is not shown — 503 {@code support_log_unavailable});
 * <b>everything else is fail-open</b> ({@link #bestEffort}: a refusal, or a write
 * already sealed on the audit chain, must not turn into a different error
 * because a log row failed). A best-effort failure is logged by row id only —
 * never the keys.
 */
@Component
@Slf4j
public class SupportAccessLogWriter {

    /** Tolerant of unknown fields, so a row written by a later release still binds on an older one mid-rollout. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SupportAccessLogRepository repository;
    private final TransactionTemplate requiresNew;

    public SupportAccessLogWriter(SupportAccessLogRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Writes the row or refuses the call. A duplicate lookup id is rethrown as
     * {@link DataIntegrityViolationException} so the search can issue another.
     *
     * @throws SupportPolicyException 503 {@code support_log_unavailable}
     */
    public void required(SupportAccessLog row) {
        try {
            requiresNew.executeWithoutResult(status -> repository.saveAndFlush(row));
        } catch (DataIntegrityViolationException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("SUPPORT_ACCESS_LOG_WRITE_FAILED (required — the read is refused) op={} reason={}",
                    row.getOp(), e.getClass().getSimpleName());
            throw SupportPolicyException.logUnavailable();
        }
    }

    /** Writes the row if it can; never throws. */
    public void bestEffort(SupportAccessLog row) {
        try {
            requiresNew.executeWithoutResult(status -> repository.saveAndFlush(row));
        } catch (RuntimeException e) {
            log.warn("SUPPORT_ACCESS_LOG_WRITE_FAILED (best effort) op={} outcome={} reason={}",
                    row.getOp(), row.getOutcome(), e.getClass().getSimpleName());
        }
    }

    // ---- JSON columns -------------------------------------------------------------------------

    public static String json(SupportCustomerKeys keys) {
        if (keys == null) return null;
        try {
            return JSON.writeValueAsString(keys);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise customer keys", e);
        }
    }

    public static String json(Map<String, List<String>> targets) {
        if (targets == null || targets.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(targets);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise section targets", e);
        }
    }

    public static SupportCustomerKeys keys(String json) {
        if (json == null || json.isBlank()) return SupportCustomerKeys.empty();
        try {
            return JSON.readValue(json, SupportCustomerKeys.class);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable customer keys on a support lookup", e);
        }
    }

    public static Map<String, List<String>> targets(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, List<String>>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("unreadable section targets on a support lookup", e);
        }
    }
}
