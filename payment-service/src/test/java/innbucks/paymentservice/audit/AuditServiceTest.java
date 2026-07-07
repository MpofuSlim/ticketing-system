package innbucks.paymentservice.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A09: pins the payment-service tamper-evident audit — every persisted row is
 * sealed with an HMAC over its immutable fields that matches a recompute, and a
 * broken audit path never breaks the caller's money flow.
 */
class AuditServiceTest {

    private AuditEventRepository repo;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repo = mock(AuditEventRepository.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AuditService(repo, new ObjectMapper(), txManager, "test-audit-hmac-secret");
    }

    @Test
    void record_sealsRowWithAnHmacThatMatchesARecompute() {
        service.recordSuccess(
                AuditEventType.PAYMENT_CONFIRMED,
                "system", AuditService.ACTOR_TYPE_SYSTEM,
                "pay-123", "PAYMENT",
                Map.of("to", "SUCCEEDED", "amount", "50.00"), AuditContext.none());

        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repo).save(saved.capture());
        AuditEvent row = saved.getValue();
        assertEquals("PAYMENT_CONFIRMED", row.getEventType());
        assertEquals("SUCCESS", row.getOutcome());
        assertNotNull(row.getRowHmac(), "row must be sealed before persistence");
        assertEquals(service.computeHmac(row), row.getRowHmac(),
                "stored HMAC must match a recompute over the same row");
    }

    @Test
    void computeHmac_isDeterministic_andTamperSensitive() {
        AuditEvent row = AuditEvent.builder()
                .occurredAt(java.time.Instant.parse("2026-07-07T10:00:00Z"))
                .eventType("PAYMENT_FAILED").outcome("FAILURE")
                .actorId("system").actorType("SYSTEM")
                .targetId("pay-9").targetType("PAYMENT")
                .failureReason("expired").metadata("{\"to\":\"EXPIRED\"}")
                .build();

        String h1 = service.computeHmac(row);
        assertEquals(h1, service.computeHmac(row), "same row must hash the same");

        row.setOutcome("SUCCESS"); // an attacker flips a FAILURE to look SUCCESS
        assertNotEquals(h1, service.computeHmac(row), "any field change must change the HMAC");
    }

    @Test
    void computeHmac_differentKey_differentTag() {
        AuditEvent row = AuditEvent.builder()
                .occurredAt(java.time.Instant.parse("2026-07-07T10:00:00Z"))
                .eventType("PAYMENT_RECON_DISCREPANCY").outcome("FAILURE")
                .actorType("SYSTEM").targetType("RECON_RUN").build();

        AuditService other = new AuditService(repo, new ObjectMapper(),
                mock(PlatformTransactionManager.class), "a-different-secret");
        assertNotEquals(service.computeHmac(row), other.computeHmac(row),
                "the HMAC key is what a DB-only attacker lacks — a different key must yield a different tag");
    }

    @Test
    void record_dbFailureIsSwallowed_neverBreaksThePaymentFlow() {
        when(repo.save(any(AuditEvent.class)))
                .thenThrow(new DataIntegrityViolationException("simulated audit DB outage"));

        assertDoesNotThrow(() -> service.recordSuccess(
                AuditEventType.PAYMENT_CODE_GENERATED,
                "system", AuditService.ACTOR_TYPE_SYSTEM,
                "pay-123", "PAYMENT", null, AuditContext.none()));
    }
}
