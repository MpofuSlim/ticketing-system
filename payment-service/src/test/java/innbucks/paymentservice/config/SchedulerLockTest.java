package innbucks.paymentservice.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the rule that makes payment-service safe to run as more than one
 * replica: every {@code @Scheduled} method carries a {@code @SchedulerLock}.
 *
 * <p>Nothing else would catch a new sweep added without one — it compiles,
 * boots and passes every test on a single replica, and only misbehaves once a
 * second pod starts polling the same payment rows. The durations are pinned
 * too, because {@link KeepAliveLockProvider} throws at LOCK time (every tick,
 * at runtime) on a {@code lockAtMostFor} under 30 seconds: the sweep would
 * never run, and on this service that means no payment ever confirms.
 *
 * <p>Pure JUnit: scans the compiled classes, no Spring context or database.
 */
class SchedulerLockTest {

    private static final Duration KEEP_ALIVE_MINIMUM = Duration.ofSeconds(30);

    @Test
    void everyScheduledMethodIsLocked_withAUniqueName_andDurationsTheKeepAliveAccepts() throws Exception {
        List<Method> scheduled = scheduledMethods();
        assertThat(scheduled).as("scheduled methods found — the scan itself must not be empty")
                .hasSizeGreaterThanOrEqualTo(6);

        Map<String, Method> byLockName = new HashMap<>();
        for (Method m : scheduled) {
            String where = m.getDeclaringClass().getSimpleName() + "." + m.getName();
            SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
            assertThat(lock).as(where + " is @Scheduled but has no @SchedulerLock — "
                    + "every replica would run it at once").isNotNull();

            Method clash = byLockName.put(lock.name(), m);
            assertThat(clash).as(where + " reuses lock name '" + lock.name()
                    + "' — two jobs sharing a lock would starve each other").isNull();

            Duration atMost = Duration.parse(lock.lockAtMostFor());
            Duration atLeast = Duration.parse(lock.lockAtLeastFor());
            assertThat(atMost).as(where + " lockAtMostFor — KeepAliveLockProvider refuses < 30s at runtime")
                    .isGreaterThanOrEqualTo(KEEP_ALIVE_MINIMUM);
            assertThat(atLeast).as(where + " lockAtLeastFor must not exceed lockAtMostFor")
                    .isLessThanOrEqualTo(atMost);

            if (!m.getAnnotation(Scheduled.class).cron().isBlank()) {
                // A cron fires on every replica at the same second; a hold
                // shorter than the clock skew lets the slower pod run it again.
                assertThat(atLeast).as(where + " is a cron job — lockAtLeastFor must outlast pod clock skew")
                        .isGreaterThanOrEqualTo(Duration.ofMinutes(1));
            }
        }
    }

    @Test
    void theLockProviderIsKeptAlive() {
        LockProvider provider = new SchedulerLockConfig(mock(DataSource.class))
                .lockProvider(mock(ScheduledExecutorService.class));
        // A bare JdbcTemplateLockProvider would drop the lock at lockAtMostFor
        // even while a slow pass is still running, letting a second replica
        // start the same sweep.
        assertThat(provider).isInstanceOf(KeepAliveLockProvider.class);
    }

    private static List<Method> scheduledMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Method> found = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("innbucks.paymentservice")) {
            for (Method m : Class.forName(bd.getBeanClassName()).getDeclaredMethods()) {
                if (m.isAnnotationPresent(Scheduled.class)) {
                    found.add(m);
                }
            }
        }
        return found;
    }
}
