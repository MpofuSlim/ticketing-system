package innbucks.paymentservice.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.config.PaymentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * A09: the verifier is what makes the seal actionable — it recomputes each
 * row's HMAC and chain link and flags mismatches so an alert can fire. Pins that
 * a content-altered row is caught as {@code tampered}, a DELETED row is caught as
 * a {@code chainBroken} link (its own row_hmac says nothing), an unsealed/legacy
 * row is NOT mistaken for either, and both counts are exported for alerting.
 */
class AuditIntegrityVerifierTest {

    private AuditEventRepository repo;
    private AuditService auditService;
    private PaymentMetrics metrics;
    private AuditIntegrityVerifier verifier;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        auditService = new AuditService(mock(AuditEventRepository.class),
                mock(AuditChainHeadRepository.class), new ObjectMapper(),
                mock(PlatformTransactionManager.class), "test-audit-hmac-secret");
        metrics = mock(PaymentMetrics.class);
        verifier = new AuditIntegrityVerifier(repo, auditService, metrics, 5000);
    }

    private AuditEvent row(String type) {
        return AuditEvent.builder()
                .occurredAt(Instant.parse("2026-07-07T10:00:00Z"))
                .eventType(type).outcome("SUCCESS")
                .actorId("system").actorType("SYSTEM")
                .targetId("pay-" + type).targetType("PAYMENT")
                .build();
    }

    /** Seal + chain a run of rows exactly as the write path would, oldest-first. */
    private List<AuditEvent> sealedChain(int n) {
        List<AuditEvent> rows = new ArrayList<>();
        String prevChain = null;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = row("PAYMENT_EVT_" + i);
            e.setId((long) i);
            e.setRowHmac(auditService.computeHmac(e));
            e.setChainHmac(auditService.computeChainHmac(prevChain, e.getRowHmac()));
            prevChain = e.getChainHmac();
            rows.add(e);
        }
        return rows;
    }

    /** The verifier scans newest-first (DESC by id). */
    @SuppressWarnings("unchecked")
    private void feed(List<AuditEvent> ascRows) {
        List<AuditEvent> desc = new ArrayList<>(ascRows);
        Collections.reverse(desc);
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(desc));
    }

    @Test
    void verifyRecent_flagsTampered_ignoresLegacy_countsOk() {
        AuditEvent clean = row("PAYMENT_CONFIRMED");
        clean.setRowHmac(auditService.computeHmac(clean));   // correctly sealed

        AuditEvent tampered = row("PAYMENT_FAILED");
        tampered.setOutcome("FAILURE");
        tampered.setRowHmac(auditService.computeHmac(tampered));
        tampered.setTargetId("HACKED");                      // altered AFTER sealing -> HMAC no longer matches

        AuditEvent legacy = row("PAYMENT_CODE_GENERATED");
        legacy.setRowHmac(null);                             // never sealed -> unverifiable, not tampered

        when(repo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(clean, tampered, legacy)));

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(3, r.checked());
        assertEquals(1, r.ok());
        assertEquals(1, r.tampered());
        assertEquals(1, r.legacy());
        verify(metrics).auditIntegrityBroken(1L);
    }

    @Test
    void verifyRecent_allClean_reportsZeroTampered() {
        AuditEvent a = row("PAYMENT_CONFIRMED");
        a.setRowHmac(auditService.computeHmac(a));
        AuditEvent b = row("PAYMENT_STATUS_UNKNOWN");
        b.setRowHmac(auditService.computeHmac(b));
        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(a, b)));

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();

        assertEquals(2, r.ok());
        assertEquals(0, r.tampered());
        verify(metrics).auditIntegrityBroken(0L);
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
    void legacyPreV10Rows_areNotMistakenForAChainBreak() {
        AuditEvent legacy1 = row("PAYMENT_CONFIRMED");
        legacy1.setId(1L);
        legacy1.setRowHmac(auditService.computeHmac(legacy1));   // sealed, no chain
        legacy1.setChainHmac(null);
        AuditEvent legacy2 = row("PAYMENT_FAILED");
        legacy2.setId(2L);
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
