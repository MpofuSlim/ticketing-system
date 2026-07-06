package com.innbucks.userservice.service;

import com.innbucks.userservice.config.SecurityMetrics;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.repository.AuditEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Verifies the tamper-evidence HMAC on {@code audit_events} rows (OWASP A09).
 *
 * <p>{@link AuditService} seals every row with an HMAC over its immutable fields
 * (keyed by {@code audit.hmac-secret}, which lives in config/env, never in the
 * DB). This component recomputes that HMAC and compares it to the stored value,
 * so a row silently altered by anyone with DB write access is detectable — the
 * forger can't produce a matching HMAC without the key.
 *
 * <p>Runs on a schedule over the most recent {@code audit.integrity.verify-limit}
 * rows and exports {@link SecurityMetrics#auditIntegrityBroken(long)} so
 * {@code prometheus/alerts.yaml} can page on any nonzero result (the invariant
 * is zero). Rows written before V29 carry no HMAC and are counted as
 * {@code legacy} (unverifiable), never as {@code tampered}. Read-only and
 * idempotent, so running it on every instance is harmless.
 */
@Component
@Slf4j
public class AuditIntegrityVerifier {

    private final AuditEventRepository repository;
    private final AuditService auditService;
    private final SecurityMetrics securityMetrics;

    /** How many of the newest rows to check per run. Bounds the scan cost on a
     *  table that grows with login volume; the alert catches tampering within
     *  this window, and an ops-run full sweep can widen it for an investigation. */
    private final int verifyLimit;

    public AuditIntegrityVerifier(AuditEventRepository repository,
                                  AuditService auditService,
                                  SecurityMetrics securityMetrics,
                                  @Value("${audit.integrity.verify-limit:5000}") int verifyLimit) {
        this.repository = repository;
        this.auditService = auditService;
        this.securityMetrics = securityMetrics;
        this.verifyLimit = verifyLimit;
    }

    /** Outcome of a verification pass. {@code tampered > 0} is a security incident. */
    public record Result(int checked, int ok, int tampered, int legacy) {}

    @Scheduled(cron = "${audit.integrity.verify-cron:0 30 3 * * *}")
    public void scheduledVerify() {
        Result r = verifyRecent();
        if (r.tampered() > 0) {
            log.error("AUDIT_INTEGRITY_BROKEN checked={} ok={} TAMPERED={} legacy={} — "
                    + "an audit_events row failed HMAC verification; investigate immediately",
                    r.checked(), r.ok(), r.tampered(), r.legacy());
        } else {
            log.info("Audit integrity verified: checked={} ok={} legacy={} (no tampering)",
                    r.checked(), r.ok(), r.legacy());
        }
    }

    /**
     * Recompute + compare the HMAC on the most recent {@code verifyLimit} rows.
     * Emits {@code security.audit.integrity.broken} for the tampered count so an
     * alert can fire even if nobody reads the logs. Never throws — a verifier
     * that crashes is worse than one that reports zero.
     */
    public Result verifyRecent() {
        int checked = 0, ok = 0, tampered = 0, legacy = 0;
        try {
            List<AuditEvent> rows = repository.findAll(
                    PageRequest.of(0, verifyLimit, Sort.by(Sort.Direction.DESC, "id"))).getContent();
            for (AuditEvent row : rows) {
                checked++;
                String stored = row.getRowHmac();
                if (stored == null || stored.isBlank()) {
                    legacy++;                       // pre-V29 row: unverifiable, not tampered
                } else if (stored.equals(auditService.computeHmac(row))) {
                    ok++;
                } else {
                    tampered++;
                    log.error("AUDIT_ROW_TAMPERED id={} eventType={} occurredAt={} — "
                            + "stored HMAC does not match recomputed",
                            row.getId(), row.getEventType(), row.getOccurredAt());
                }
            }
        } catch (RuntimeException ex) {
            log.error("Audit integrity verification pass failed to run: {}", ex.getMessage(), ex);
        }
        securityMetrics.auditIntegrityBroken(tampered);
        return new Result(checked, ok, tampered, legacy);
    }
}
