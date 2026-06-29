package com.innbucks.userservice.config;

import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async} app-wide
 * and provides the executor every async-listener method uses. Today's only
 * consumer is {@code CredentialDeliveryListener} (moves the email/SMS/WhatsApp
 * fan-out off the HTTP request thread so {@code PUT /admin/users/{id}/active}
 * returns as soon as the DB commits — the original symptom was a 30-second
 * FE-side timeout on activation while the email gateway hung at 403).
 *
 * <p>The pool is deliberately small + bounded:
 * <ul>
 *   <li>core 2 / max 8 covers admin-driven activation and security-alert burst rates,
 *       not customer traffic.</li>
 *   <li>Queue capacity 100: enough to absorb a SUPER_ADMIN approving a backlog
 *       in one sitting; small enough to surface upstream stalls.</li>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy}: if the queue fills, the
 *       caller (the request thread) runs the task itself. Slower than rejecting
 *       silently, but we never drop a credential-delivery attempt — at worst we
 *       degrade to the pre-fix behaviour for that one burst rather than
 *       losing the work.</li>
 *   <li>Uncaught exceptions go to {@link SimpleAsyncUncaughtExceptionHandler}
 *       which logs them. Async listeners catch their own gateway exceptions
 *       (see CredentialDeliveryListener); this handler is the defence in depth
 *       for anything that escapes.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 30s is well over the 5s+5s+5s tightened gateway timeouts a single
        // delivery attempt can incur, but short enough that a wedged pool
        // won't block a graceful container shutdown indefinitely.
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return notificationExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
