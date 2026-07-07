package com.innbucks.userservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.userservice.config.SecurityMetrics;
import com.innbucks.userservice.entity.AuditEvent;
import com.innbucks.userservice.repository.AuditChainHeadRepository;
import com.innbucks.userservice.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A09: pins the two forensic guarantees of the audit log.
 * <ul>
 *   <li><b>Row HMAC</b> — a content-altered row is caught as {@code tampered}
 *       and a pre-V29 (null-HMAC) row is not mistaken for tampering.</li>
 *   <li><b>Hash-chain</b> — a DELETED row (whose own {@code row_hmac} says
 *       nothing) is caught because the link at the next surviving row no longer
 *       recomputes, exported as {@code chainBroken}; an intact chain reports zero;
 *       pre-V32 (null-chain) rows are not mistaken for a break.</li>
 * </ul>
 */
class AuditIntegrityVerifierTest {

    private AuditEventRepository repo;
    private AuditService auditService;
    private SecurityMetrics metrics;
    private AuditIntegrityVerifier verifier;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        // A real service so computeHmac / computeChainHmac use the same key the
        // verifier recomputes with; its own repos are irrelevant here.
        auditService = new AuditService(mock(AuditEventRepository.class),
                mock(AuditChainHeadRepository.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class), "test-audit-hmac-secret");
        metrics = mock(SecurityMetrics.class);
        verifier = new AuditIntegrityVerifier(repo, auditService, metrics, 5000);
    }

    private AuditEvent row(long id, String type) {
        AuditEvent e = AuditEvent.builder()
                .occurredAt(Instant.parse("2026-07-07T10:00:00Z"))
                .eventType(type).outcome("SUCCESS")
                .actorId("42").actorType("USER")
                .targetId("42").targetType("USER")
                .build();
        e.setId(id);
        return e;
    }

    /** Seal + chain a run of rows exactly as the write path would, oldest-first. */
    private List<AuditEvent> sealedChain(int n) {
        List<AuditEvent> rows = new ArrayList<>();
        String prevChain = null;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = row(i, "EVT_" + i);
            e.setRowHmac(auditService.computeHmac(e));
            e.setChainHmac(auditService.computeChainHmac(prevChain, e.getRowHmac()));
            prevChain = e.getChainHmac();
            rows.add(e);
        }
        return rows;
    }

    /** The verifier scans newest-first (DESC by id). */
    private void feed(List<AuditEvent> ascRows) {
        List<AuditEvent> desc = new ArrayList<>(ascRows);
        Collections.reverse(desc);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(desc));
    }

    @Test
    void intactChain_reportsZeroBroken() {
        feed(sealedChain(4));

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(4, r.checked());
        assertEquals(4, r.ok());          // all content intact
        assertEquals(0, r.tampered());
        assertEquals(3, r.chainOk());     // 4 rows, first is the anchor
        assertEquals(0, r.chainBroken());
        verify(metrics).auditIntegrityBroken(0L);
        verify(metrics).auditChainBroken(0L);
    }

    @Test
    void deletingARow_breaksTheChain_atTheNextSurvivor() {
        List<AuditEvent> chain = sealedChain(4);   // r1..r4, all validly linked
        // Attacker deletes r2 (index 1) — its own row_hmac would have verified,
        // so ONLY the chain catches this.
        List<AuditEvent> withHole = new ArrayList<>(chain);
        withHole.remove(1);
        feed(withHole);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(3, r.checked());
        assertEquals(3, r.ok(), "the surviving rows' content is untouched");
        assertEquals(0, r.tampered());
        assertEquals(1, r.chainBroken(), "the gap surfaces once, at the row after the deletion");
        verify(metrics).auditChainBroken(1L);
    }

    @Test
    void contentTamperedRow_isFlagged_byRowHmac() {
        List<AuditEvent> chain = sealedChain(2);
        chain.get(1).setOutcome("FAILURE");        // altered AFTER sealing
        feed(chain);

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(1, r.tampered());
        verify(metrics).auditIntegrityBroken(1L);
    }

    @Test
    void legacyPreV32Rows_areNotMistakenForAChainBreak() {
        AuditEvent legacy1 = row(1, "OLD_1");
        legacy1.setRowHmac(auditService.computeHmac(legacy1));   // V29 row: sealed, no chain
        legacy1.setChainHmac(null);
        AuditEvent legacy2 = row(2, "OLD_2");
        legacy2.setRowHmac(auditService.computeHmac(legacy2));
        legacy2.setChainHmac(null);
        feed(List.of(legacy1, legacy2));

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(2, r.ok());
        assertEquals(0, r.chainBroken(), "null-chain rows are legacy, never a break");
        assertEquals(0, r.chainOk());
        verify(metrics).auditChainBroken(0L);
    }
}
