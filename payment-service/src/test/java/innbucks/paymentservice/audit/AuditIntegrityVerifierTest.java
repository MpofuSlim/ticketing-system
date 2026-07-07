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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A09: the verifier is what makes the seal actionable — it recomputes each
 * row's HMAC and flags mismatches so an alert can page. Pins that a
 * content-altered row is caught as {@code tampered}, an unsealed (legacy) row is
 * NOT mistaken for tampering, and the tampered count is exported for alerting.
 */
class AuditIntegrityVerifierTest {

    private AuditEventRepository repo;
    private AuditService auditService;
    private PaymentMetrics metrics;
    private AuditIntegrityVerifier verifier;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        auditService = new AuditService(mock(AuditEventRepository.class), new ObjectMapper(),
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
}
