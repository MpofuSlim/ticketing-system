package com.innbucks.bookingservice.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The executor the post-commit side effects of a booking run on: ticket
 * delivery on confirm, the cancellation notice, and the event-service
 * availability decrement ({@code @Async("ticketDeliveryExecutor")}).
 *
 * <p><b>Why these must not run on the committing thread.</b> A synchronous
 * {@code @TransactionalEventListener(AFTER_COMMIT)} runs from
 * {@code triggerAfterCommit}, which comes BEFORE {@code cleanupAfterCompletion}
 * — so the committed transaction's pooled connection is still held for the
 * whole WhatsApp/SMS/email fan-out, and a {@code REQUIRES_NEW} on the listener
 * takes a SECOND one. A slow gateway therefore pinned two connections per
 * confirm on payment-service's request thread. Handing the work to this pool
 * (with no {@code @Transactional} on the listener) lets the confirming thread
 * release its connection and return, and the listener's own reads borrow a
 * connection only for the read itself.
 *
 * <p>Same shape as user-service's {@code notificationExecutor}:
 * <ul>
 *   <li>core 2 / max 8, queue 100 — sized for confirm bursts at an on-sale,
 *       small enough that a stalled gateway shows up as a growing queue rather
 *       than unbounded threads.</li>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy}: a full queue makes the
 *       publishing thread deliver itself. That degrades to the pre-async
 *       behaviour for that one burst — slower, but a ticket is never dropped.</li>
 *   <li>Waits up to 30s for queued deliveries on shutdown, so a rolling deploy
 *       does not silently discard tickets that were just paid for.</li>
 *   <li>The SLF4J MDC (correlation id, country) is copied onto the worker, so
 *       a delivery's log lines still join the confirm request that caused it.</li>
 * </ul>
 *
 * <p>Declaring an {@code Executor} bean normally switches Boot's own
 * {@code applicationTaskExecutor} off, which would silently move every plain
 * {@code @Async} (the event-change broadcast) onto this pool or onto an
 * unbounded {@code SimpleAsyncTaskExecutor}. {@code spring.task.execution.mode:
 * force} in application.yaml keeps Boot's executor — and its default-for-
 * {@code @Async} wiring — exactly as before.
 */
@Configuration
public class AsyncConfig {

    public static final String TICKET_DELIVERY_EXECUTOR = "ticketDeliveryExecutor";
    public static final String THREAD_NAME_PREFIX = "ticket-delivery-";

    @Bean(name = TICKET_DELIVERY_EXECUTOR)
    public ThreadPoolTaskExecutor ticketDeliveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(mdcPropagating());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // Comfortably over one booking's worst case (a few gateway timeouts in
        // a row), short enough that a wedged pool never blocks shutdown forever.
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * Copies the submitting thread's MDC onto the worker for the task's
     * duration, then restores whatever the worker held before — pool threads
     * are reused, so leaving the map behind would stamp the NEXT delivery
     * with this one's correlation id.
     */
    static TaskDecorator mdcPropagating() {
        return task -> {
            Map<String, String> submitted = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (submitted == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(submitted);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
