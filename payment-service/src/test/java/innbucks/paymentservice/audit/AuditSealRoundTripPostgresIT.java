package innbucks.paymentservice.audit;

import innbucks.paymentservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A row sealed by {@link AuditService} must verify after a real round trip
 * through Postgres. {@code occurred_at} is a {@code timestamptz}, which keeps
 * microseconds; a time sealed at the JVM's nanoseconds read back different, and
 * every row on production failed its own HMAC (TAMPERED=220, chain intact). The
 * mocked unit tests could not see it: a mock hands back the sealed object.
 */
class AuditSealRoundTripPostgresIT extends PostgresIntegrationTestBase {

    @Autowired private AuditService auditService;
    @Autowired private AuditEventRepository repository;
    @Autowired private AuditIntegrityVerifier verifier;

    @Test
    void aRecordedRow_readBackFromPostgres_matchesItsOwnSealExactly() {
        for (int i = 0; i < 10; i++) {
            auditService.recordSuccess(AuditEventType.PAYMENT_CONFIRMED,
                    "system", AuditService.ACTOR_TYPE_SYSTEM,
                    "round-trip-" + i, "PAYMENT", Map.of("i", i), AuditContext.none());
        }

        List<AuditEvent> rows = repository.findAll().stream()
                .filter(r -> r.getTargetId() != null && r.getTargetId().startsWith("round-trip-"))
                .toList();
        assertEquals(10, rows.size());
        for (AuditEvent row : rows) {
            assertEquals(row.getRowHmac(), auditService.computeHmac(row),
                    "row " + row.getId() + " read back at " + row.getOccurredAt() + " must match its seal");
        }

        AuditIntegrityVerifier.Result r = verifier.verifyRecent();
        assertEquals(0, r.tampered());
        assertEquals(0, r.chainBroken());
    }
}
