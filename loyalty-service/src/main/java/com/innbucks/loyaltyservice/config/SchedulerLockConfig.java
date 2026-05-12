package com.innbucks.loyaltyservice.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Enables ShedLock so the service's {@code @Scheduled} jobs are leader-elected
 * across replicas. Without this every pod runs every cron simultaneously, which
 * triples DB load and races on invoice generation.
 *
 * <p>{@code defaultLockAtMostFor = PT10M} is the fail-safe — if a pod dies mid-run
 * its lock is released after 10 minutes so another pod can pick up the next
 * scheduled tick. Each @SchedulerLock annotation can override this for its own
 * job (long-running ones should set lockAtMostFor higher).
 *
 * <p>Storage is the {@code shedlock} table created in V6, talking to the main
 * Hikari pool — no extra DataSource needed.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
