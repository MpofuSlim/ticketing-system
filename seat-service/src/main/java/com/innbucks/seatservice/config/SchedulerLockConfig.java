package com.innbucks.seatservice.config;

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
 * Enables ShedLock so seat-service's only @Scheduled job — {@link
 * com.innbucks.seatservice.service.SeatLockReaper#reap()} — is leader-
 * elected across replicas. Without this, every pod ran the reaper every
 * minute simultaneously: same expired-lock candidates pulled N times,
 * each thread then attempting per-row pessimistic locks via
 * {@code SeatService.releaseStaleLock} and tripping on each other.
 * Optimistic locking on Seat prevented bad writes but the wasted query
 * traffic + lock-contention noise was real.
 *
 * <p>{@code defaultLockAtMostFor = PT5M} is the fail-safe — a pod that
 * dies mid-reap releases its lock after 5 minutes so the next tick can
 * proceed. The reaper itself bounds work via {@code batchSize}, so a
 * single reap should always finish well inside that window.
 *
 * <p>Mirrors loyalty-service's SchedulerLockConfig. Storage is the
 * {@code shedlock} table provisioned by V4 Flyway migration (and by
 * the idempotent CREATE in {@link #ensureShedlockTable()} for the H2
 * test profile where Flyway is disabled).
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
