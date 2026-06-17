package com.innbucks.eventservice.repository;

import com.innbucks.eventservice.entity.Event;
import com.innbucks.eventservice.entity.EventCategory;
import com.innbucks.eventservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the 2026-06-17 startup crash: {@code
 * TenantUserUuidBackfillRunner} invoked its own {@code @Transactional} helper
 * via self-invocation, which bypasses Spring's proxy, so the {@code @Modifying}
 * backfill UPDATE ran with no active transaction and threw
 * {@code TransactionRequiredException} — failing event-service startup whenever
 * there were legacy rows to migrate. The fix moved {@code @Transactional} onto
 * the repository method itself.
 *
 * <p>Crucially this class is NOT {@code @Transactional} (unlike its sibling
 * {@link EventRepositoryPostgresIT}), so the call below runs with no ambient
 * transaction — exactly the ApplicationRunner condition. If the repository
 * annotation regresses, {@code backfillTenantUserUuid} throws here and the test
 * fails. Skipped without Docker via the base's {@code @EnabledIf}.
 */
class TenantUserUuidBackfillPostgresIT extends PostgresIntegrationTestBase {

    @Autowired EventRepository eventRepository;

    @Test
    void backfillTenantUserUuid_runsWithoutAnAmbientTransaction() {
        String tenantId = "backfill-it-" + UUID.randomUUID();
        Event saved = eventRepository.save(Event.builder()
                .tenantId(tenantId)
                .title("Backfill Regression")
                .venue("HICC")
                .country("Zimbabwe")
                .category(EventCategory.CONCERT)
                .startDateTime(LocalDateTime.of(2030, 6, 1, 18, 0))
                .endDateTime(LocalDateTime.of(2030, 6, 1, 20, 0))
                .totalCapacity(100)
                .availableTickets(100)
                .active(true)
                .deleted(false)
                .build());
        // New rows have no cross-service uuid until backfill (or dual-write) runs.
        assertThat(saved.getTenantUserUuid()).isNull();

        UUID organizerUuid = UUID.randomUUID();
        try {
            // The call under test — no surrounding transaction. Pre-fix this
            // threw TransactionRequiredException.
            int updated = eventRepository.backfillTenantUserUuid(tenantId, organizerUuid);
            assertThat(updated).isEqualTo(1);

            Event reloaded = eventRepository.findById(saved.getEventId()).orElseThrow();
            assertThat(reloaded.getTenantUserUuid()).isEqualTo(organizerUuid);
        } finally {
            eventRepository.deleteById(saved.getEventId());
        }
    }
}
