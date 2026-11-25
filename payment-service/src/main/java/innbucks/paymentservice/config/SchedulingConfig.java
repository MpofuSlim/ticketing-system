package innbucks.paymentservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Enables {@link org.springframework.scheduling.annotation.Scheduled @Scheduled}
 * processing across the service. Required for
 * {@link innbucks.paymentservice.reconciliation.PaymentResolutionJob} — the
 * payment confirmation path — and every other periodic task; each one also
 * carries a {@code @SchedulerLock} (see {@link SchedulerLockConfig}). Spring Boot does not auto-enable this — the
 * annotation is opt-in.
 *
 * <p><b>The jobs get their own pool.</b> Without a {@code TaskScheduler} bean,
 * Spring runs every {@code @Scheduled} method on the one
 * {@code ScheduledExecutorService} in the context, which is
 * {@link SchedulerLockConfig#shedlockKeepAliveExecutor()}: a single thread. All
 * seven jobs then took turns on it, so one upstream that hangs (a poll costs up
 * to three reads at its read timeout) stalled every other rail's poller, and
 * the keep-alive could not renew a lock while the job holding it ran on the
 * same thread. With this pool each job has a thread of its own; a job still
 * never overlaps itself ({@code fixedDelay} and its lock), and the per-row
 * money rules already allow a concurrent reader (the customer's instant
 * check, the webhooks, a second replica).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    /** At least one thread per {@code @Scheduled} method ({@code SchedulingPoolTest} counts them). */
    static final int POOL_SIZE = 8;

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("payment-jobs-");
        return scheduler;
    }
}
