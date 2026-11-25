package innbucks.paymentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the scheduler pool: every {@code @Scheduled} job has a thread of its
 * own, apart from the lock keep-alive thread. On a shared single thread, one
 * upstream that hangs stalls every other rail's poller, and the keep-alive
 * cannot renew the lock of the job holding its thread.
 *
 * <p>Pure JUnit: scans the compiled classes, no Spring context.
 */
class SchedulingPoolTest {

    @Test
    void everyScheduledJobHasAThread_offTheKeepAliveThread() throws Exception {
        long jobs = scheduledMethodCount();
        assertThat(jobs).as("scheduled methods found — the scan itself must not be empty").isGreaterThanOrEqualTo(7);

        ThreadPoolTaskScheduler scheduler = new SchedulingConfig().taskScheduler();
        try {
            scheduler.initialize();
            // The configured size; getPoolSize() is the live thread count.
            assertThat((long) scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .as("one thread per @Scheduled method: raise SchedulingConfig.POOL_SIZE with a new job")
                    .isGreaterThanOrEqualTo(jobs);
            assertThat(scheduler.getThreadNamePrefix()).isNotEqualTo("shedlock-keepalive");
        } finally {
            scheduler.shutdown();
        }
    }

    private static long scheduledMethodCount() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        long count = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("innbucks.paymentservice")) {
            count += Arrays.stream(Class.forName(bd.getBeanClassName()).getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Scheduled.class))
                    .count();
        }
        return count;
    }
}
