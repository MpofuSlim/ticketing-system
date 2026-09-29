package innbucks.paymentservice.config;

import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Leader-elects every {@code @Scheduled} job in payment-service, so the
 * service can run more than one replica without running a sweep twice at
 * once.
 *
 * <p><b>Why it matters here more than anywhere else in the fleet.</b> The
 * sweeps in {@link innbucks.paymentservice.reconciliation.PaymentResolutionJob}
 * ARE the payment confirmation path. Two replicas polling the same
 * {@code TOKEN_ISSUED} rows double every upstream status read, and a
 * ZimSwitch final-status read is ONE-SHOT and throttled upstream to two per
 * checkout per minute (CLAUDE.md, card rail). The per-row money rules already
 * tolerate a concurrent reader (the customer's instant check and the EcoCash
 * notify webhook run the same resolvers), so this is not the only guard — it
 * removes the replica-vs-replica half of the contention.
 *
 * <p><b>Why {@link KeepAliveLockProvider}.</b> A pass is bounded by rows, not
 * time: 100 open codes against a slow InnBucks at a 20s read timeout is well
 * over half an hour. A fixed {@code lockAtMostFor} long enough to cover that
 * would stall payment confirmation for the same half hour whenever the lock
 * holder died. The keep-alive wrapper extends the lock every
 * {@code lockAtMostFor / 2} while the holder is still running, so a live pass
 * never loses its lock and a dead one frees it within {@code lockAtMostFor}.
 * It refuses a {@code lockAtMostFor} under 30 seconds, which is why every
 * {@code @SchedulerLock} here uses at least that; {@code SchedulerLockTest}
 * pins it.
 *
 * <p>Storage is the {@code shedlock} table (V15), also created at boot by
 * {@link #ensureShedlockTable()} for a cell run with {@code FLYWAY_ENABLED=false}:
 * without the table every lock attempt fails and no sweep runs at all, which
 * on this service means no payment ever confirms. Same shape as booking-service
 * and seat-service.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
public class SchedulerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockConfig.class);

    private final DataSource dataSource;

    public SchedulerLockConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** One daemon thread that renews held locks; stopped with the context. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService shedlockKeepAliveExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shedlock-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public LockProvider lockProvider(ScheduledExecutorService shedlockKeepAliveExecutor) {
        JdbcTemplateLockProvider jdbc = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // The database clock decides expiry, so replicas with
                        // skewed clocks still agree on who holds a lock.
                        .usingDbTime()
                        .build());
        return new KeepAliveLockProvider(jdbc, shedlockKeepAliveExecutor);
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
        } catch (Exception e) {
            log.error("Could not ensure the shedlock table exists — scheduled payment sweeps "
                    + "cannot take their locks and will not run: {}", e.getMessage());
        }
    }
}
