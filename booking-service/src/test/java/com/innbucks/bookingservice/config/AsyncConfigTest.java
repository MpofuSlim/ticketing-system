package com.innbucks.bookingservice.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket-delivery pool's shape (bounded, never drops work) and its MDC
 * hand-off: the worker sees the submitter's correlation id, and a reused
 * worker thread does not carry it into the next task.
 */
class AsyncConfigTest {

    private final ThreadPoolTaskExecutor executor = new AsyncConfig().ticketDeliveryExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdown();
        MDC.clear();
    }

    @Test
    void poolIsBounded_andRunsOnTheCallerWhenFull() {
        executor.initialize();
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(8);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Test
    void mdcFollowsTheTask_andIsNotLeftOnTheWorker() throws Exception {
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();

        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        CompletableFuture<String> seen = new CompletableFuture<>();
        executor.execute(() -> seen.complete(MDC.get(CorrelationIdFilter.MDC_KEY)));
        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("corr-123");

        // Same single worker, submitted with an empty MDC.
        MDC.clear();
        CompletableFuture<String> next = new CompletableFuture<>();
        executor.execute(() -> next.complete(String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY))));
        assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("null");
    }
}
