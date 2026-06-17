package com.innbucks.eventservice.service;

import com.innbucks.eventservice.client.UserUuidLookupGateway;
import com.innbucks.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfill for {@code events.tenant_user_uuid}. Every event created from
 * V6 onward dual-writes the column at INSERT time; this runner closes the
 * gap for rows that pre-date the migration.
 *
 * <p>Strategy: at startup AND every 5 minutes thereafter (so a deploy
 * that races user-service's V20 still converges), batch-fetch up to
 * {@link #batchSize} distinct {@code tenant_id} emails that haven't been
 * resolved, call user-service to map them to {@code user_uuid}, and
 * write the result back. Idempotent — the UPDATE only touches rows whose
 * column is still null, so a re-run after success is a no-op.
 *
 * <p>Disabled in tests / when user-service is unreachable: the gateway
 * returns an empty map on failure and the runner simply logs and tries
 * again on the next tick. Failure here never blocks startup or breaks
 * request handling — the legacy email-based query path keeps serving
 * pre-V6 rows until backfill completes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantUserUuidBackfillRunner implements ApplicationRunner {

    private final EventRepository eventRepository;
    private final UserUuidLookupGateway userUuidLookupGateway;

    /** Cap per pass so a deployment with millions of rows doesn't lock the
     *  table or hammer user-service. Five passes (1000 rows each) per tick
     *  is enough headroom for any realistic backlog. */
    @Value("${innbucks.tenant-uuid-backfill.batch-size:1000}")
    private int batchSize;

    @Value("${innbucks.tenant-uuid-backfill.max-passes-per-tick:5}")
    private int maxPassesPerTick;

    /** Master switch — tests flip this off via property to keep S2S
     *  network calls out of the SpringBootTest startup path. */
    @Value("${innbucks.tenant-uuid-backfill.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Tenant-user-uuid backfill disabled by config");
            return;
        }
        runOnePass();
    }

    /**
     * Periodic re-run. Five minutes is short enough that a deploy ordering
     * problem (event-service up before user-service has the user_uuid
     * column populated) converges quickly, long enough that a healthy
     * cluster doesn't churn the lookup endpoint after backfill is done
     * (steady-state: every tick finds zero unresolved rows and returns).
     */
    @Scheduled(fixedDelayString = "${innbucks.tenant-uuid-backfill.fixed-delay-ms:300000}",
               initialDelayString = "${innbucks.tenant-uuid-backfill.initial-delay-ms:300000}")
    public void scheduledTick() {
        if (!enabled) return;
        runOnePass();
    }

    void runOnePass() {
        int totalUpdated = 0;
        for (int pass = 0; pass < maxPassesPerTick; pass++) {
            List<String> emails = eventRepository.findDistinctTenantIdsMissingUuid(batchSize);
            if (emails.isEmpty()) {
                break;
            }
            Map<String, UUID> resolved = userUuidLookupGateway.resolveUuidsByEmail(emails);
            if (resolved.isEmpty()) {
                // user-service unreachable or none of these emails resolve
                // to a real user (orphan tenant ids on legacy data). Give
                // up this tick rather than spin forever.
                log.info("Backfill pass {} resolved 0 of {} emails; deferring rest", pass, emails.size());
                break;
            }
            int updatedThisPass = applyResolutions(resolved);
            totalUpdated += updatedThisPass;
            // If we resolved fewer than batchSize, there's no point pulling
            // another page — we'd just get the same set back.
            if (emails.size() < batchSize) {
                break;
            }
        }
        if (totalUpdated > 0) {
            log.info("Tenant-user-uuid backfill updated {} event rows", totalUpdated);
        }
    }

    @Transactional
    protected int applyResolutions(Map<String, UUID> resolved) {
        int updated = 0;
        for (Map.Entry<String, UUID> entry : resolved.entrySet()) {
            updated += eventRepository.backfillTenantUserUuid(entry.getKey(), entry.getValue());
        }
        return updated;
    }
}
