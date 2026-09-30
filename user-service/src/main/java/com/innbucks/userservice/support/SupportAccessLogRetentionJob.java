package com.innbucks.userservice.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Keeps {@code support_access_log} to 12 months (design §3.1.2): long enough to
 * answer "who looked at this customer, and when?" for any complaint or dispute
 * the business still has to answer, and no longer — the rows hold customer keys
 * in full.
 *
 * <p><b>Why there is no {@code @SchedulerLock} (the CLAUDE.md replica rule).</b>
 * user-service has no ShedLock, and this job does not need one: it is a single
 * {@code DELETE … WHERE created_at < :cutoff}, which is IDEMPOTENT (a second run
 * deletes nothing the first did not) and SAFE TO RUN CONCURRENTLY on N replicas —
 * two overlapping deletes of the same rows serialise on Postgres row locks, and
 * the loser simply finds them gone. It changes nothing but the expired rows, so
 * running it once per replica per night costs one extra no-op statement each.
 * ({@code DeviceSecurityRetentionJob} runs unlocked for the same reason.)
 *
 * <p>{@code support_actions} is deliberately NOT swept: it is the idempotency
 * record of support WRITES, small, and the join from the audit chain's
 * {@code idempotencyKey} to what the agent was told.
 */
@Component
@Slf4j
public class SupportAccessLogRetentionJob {

    /** 12 months, per the design. */
    static final int RETENTION_MONTHS = 12;

    private final SupportAccessLogRepository repository;
    private final Clock clock;

    public SupportAccessLogRetentionJob(SupportAccessLogRepository repository,
                                        @Qualifier("supportClock") Clock supportClock) {
        this.repository = repository;
        this.clock = supportClock;
    }

    @Scheduled(cron = "${support.retention.cron:0 41 2 * * *}", zone = "UTC")
    @Transactional
    public void purge() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMonths(RETENTION_MONTHS);
        int deleted = repository.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            log.info("Support access-log retention deleted {} rows older than {}", deleted, cutoff);
        }
    }
}
