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

import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p><b>Hash-chain (V32).</b> The per-row HMAC only proves a row's content is
 * intact — it cannot detect a whole row being DELETED, REORDERED, or truncated
 * from the tail. So each row also carries a {@code chain_hmac} linking it to its
 * predecessor. This verifier additionally walks the window oldest-first and
 * recomputes each link; a mismatch means a neighbouring row was removed or moved,
 * and is exported as {@link SecurityMetrics#auditChainBroken(long)} (ticket
 * severity — surviving content is intact and a break can also be a benign secret
 * rotation, so it warrants investigation rather than an immediate page). The
 * first chained row in the window is treated as an anchor: its predecessor lies
 * outside the scanned window, so its back-link can't be checked here.
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

    /**
     * Outcome of a verification pass. {@code tampered > 0} (content altered) or
     * {@code chainBroken > 0} (row deleted/reordered) is a security incident.
     */
    public record Result(int checked, int ok, int tampered, int legacy,
                         int chainOk, int chainBroken) {}

    @Scheduled(cron = "${audit.integrity.verify-cron:0 30 3 * * *}")
    public void scheduledVerify() {
        Result r = verifyRecent();
        if (r.tampered() > 0 || r.chainBroken() > 0) {
            log.error("AUDIT_INTEGRITY_BROKEN checked={} ok={} TAMPERED={} legacy={} "
                    + "chainOk={} CHAIN_BROKEN={} — an audit_events row failed HMAC/chain "
                    + "verification; investigate immediately",
                    r.checked(), r.ok(), r.tampered(), r.legacy(), r.chainOk(), r.chainBroken());
        } else {
            log.info("Audit integrity verified: checked={} ok={} legacy={} chainOk={} "
                    + "(no tampering, chain intact)",
                    r.checked(), r.ok(), r.legacy(), r.chainOk());
        }
    }

    /**
     * Recompute + compare the HMAC on the most recent {@code verifyLimit} rows.
     * Emits {@code security.audit.integrity.broken} for the tampered count so an
     * alert can fire even if nobody reads the logs. Never throws — a verifier
     * that crashes is worse than one that reports zero.
     */
    public Result verifyRecent() {
        int checked = 0, ok = 0, tampered = 0, legacy = 0, chainOk = 0, chainBroken = 0;
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
            int[] chain = verifyChain(rows);
            chainOk = chain[0];
            chainBroken = chain[1];
        } catch (RuntimeException ex) {
            log.error("Audit integrity verification pass failed to run: {}", ex.getMessage(), ex);
        }
        securityMetrics.auditIntegrityBroken(tampered);
        securityMetrics.auditChainBroken(chainBroken);
        return new Result(checked, ok, tampered, legacy, chainOk, chainBroken);
    }

    /**
     * Walk the hash-chain oldest-first and recompute each link (OWASP A09
     * deletion/reorder detection). Returns {@code [chainOk, chainBroken]}.
     *
     * <p>{@code rows} arrives newest-first (the DESC scan), so we reverse to
     * oldest-first. The first chained row in the window is an <em>anchor</em>: its
     * predecessor lies outside the scanned window, so its back-link can't be
     * checked and it is neither ok nor broken. Each subsequent row's stored
     * {@code chain_hmac} must equal {@code HMAC(prevChain, row.rowHmac)}; a
     * mismatch means a row between them was deleted or reordered. After a break we
     * re-anchor on the stored chain value so ONE deletion flags exactly once
     * rather than cascading. A legacy (pre-V32, null chain) row resets the anchor,
     * since the chain isn't continuous across it.
     */
    private int[] verifyChain(List<AuditEvent> rows) {
        List<AuditEvent> asc = new ArrayList<>(rows);
        Collections.reverse(asc);                   // oldest-first for the forward walk
        int chainOk = 0, chainBroken = 0;
        String prevChain = null;
        boolean anchored = false;
        for (AuditEvent row : asc) {
            String storedChain = row.getChainHmac();
            if (storedChain == null || storedChain.isBlank()) {
                anchored = false;                   // pre-V32 row: chain not applicable
                prevChain = null;
                continue;
            }
            if (!anchored) {
                prevChain = storedChain;            // predecessor outside window: anchor only
                anchored = true;
                continue;
            }
            String expected = auditService.computeChainHmac(prevChain, row.getRowHmac());
            if (expected.equals(storedChain)) {
                chainOk++;
            } else {
                chainBroken++;
                log.error("AUDIT_CHAIN_BROKEN id={} eventType={} occurredAt={} — chain link "
                        + "does not recompute; a preceding audit row may have been deleted or reordered",
                        row.getId(), row.getEventType(), row.getOccurredAt());
            }
            prevChain = storedChain;                // re-anchor on stored so one break flags once
        }
        return new int[]{chainOk, chainBroken};
    }
}
