package com.innbucks.bookingservice.config;

import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Enables ShedLock so booking-service's @Scheduled jobs are leader-elected
 * across replicas. Without this, every pod runs:
 *
 *   - {@link com.innbucks.bookingservice.service.BookingExpirationService}
 *     (every 30s, picks PENDING bookings whose seat-hold has expired and
 *     cancels them). Multiple pods means each candidate is processed N
 *     times — the per-row pessimistic lock in cancelBooking catches the
 *     duplicates but burns queries and creates lock contention.
 *
 *   - {@link com.innbucks.bookingservice.loyalty.LoyaltyEarnRetryJob}
 *     (new in this commit, drains the loyalty_earn_retry table). Multiple
 *     pods here is materially worse: each tick of each pod would call
 *     loyalty.earn(...) for the same retry row, potentially double-crediting
 *     the customer. The whole point of the retry table is to avoid losing
 *     points; running it concurrent-unsafe would invert the contract.
 *
 * <p>defaultLockAtMostFor = PT5M is the fail-safe — a pod that dies mid-job
 * releases the lock after 5 minutes so the next tick proceeds. Each job
 * sets its own lockAtMostFor for tighter / looser bounds as needed.
 *
 * <p>Storage is the {@code shedlock} table provisioned by V7 Flyway
 * migration. The idempotent CREATE in {@link #ensureShedlockTable()} is
 * the safety net for the H2 test profile where Flyway is disabled.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockConfig.class);

    private final DataSource dataSource;

    public SchedulerLockConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public LockProvider lockProvider() {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    @PostConstruct
    void ensureShedlockTable() {
        try {
            new JdbcTemplate(dataSource).execute("""
                    CREATE TABLE IF NOT EXISTS shedlock (
                        name VARCHAR(64) PRIMARY KEY,
                        lock_until TIMESTAMP NOT NULL,
                        locked_at TIMESTAMP NOT NULL,
                        locked_by VARCHAR(255) NOT NULL
                    )
                    """);
            log.debug("shedlock table verified / created");
        } catch (Exception e) {
            log.warn("Could not ensure shedlock table exists: {}", e.getMessage());
        }
    }
}
