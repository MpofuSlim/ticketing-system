package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that proves the GA inventory counter can't oversell. Fires far more
 * concurrent claims than capacity at a single category row on REAL Postgres
 * (Testcontainers) and asserts:
 * <ul>
 *   <li>exactly {@code capacity} claims win,</li>
 *   <li>{@code remaining} bottoms out at 0 — never negative.</li>
 * </ul>
 *
 * <p>This is the regression that the old synthetic-seat + random-sample model
 * had no equivalent for (its concurrency was mock-tested only). The guard under
 * test is {@code CategoryInventoryRepository.tryClaim}'s
 * {@code UPDATE ... WHERE remaining >= :qty} — the row lock serialises the
 * concurrent UPDATEs and the predicate rejects the one that would underflow.
 * Each claim runs in its own transaction, mirroring how booking-create commits.
 */
class CategoryInventoryConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired
    private CategoryInventoryRepository inventoryRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void tryClaim_underHeavyConcurrency_neverOversellsAndBottomsOutAtZero() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID categoryId = UUID.randomUUID();
        int capacity = 50;
        int contenders = 250; // 5x oversubscription

        tx.executeWithoutResult(s -> inventoryRepo.seedIfAbsent(categoryId, capacity));

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                futures.add(pool.submit(() -> {
                    start.await(); // release all threads at once for maximum contention
                    Integer claimed = tx.execute(st -> inventoryRepo.tryClaim(categoryId, 1));
                    if (claimed != null && claimed == 1) {
                        wins.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int remaining = inventoryRepo.findById(categoryId).orElseThrow().getRemaining();
        assertEquals(capacity, wins.get(),
                "exactly capacity claims should win; got " + wins.get());
        assertEquals(0, remaining, "remaining must bottom out at 0");
        assertTrue(remaining >= 0, "remaining must never go negative");
    }

    @Test
    void release_returnsTicketsToTheCounter() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID categoryId = UUID.randomUUID();

        tx.executeWithoutResult(s -> inventoryRepo.seedIfAbsent(categoryId, 10));
        tx.executeWithoutResult(s -> inventoryRepo.tryClaim(categoryId, 4)); // remaining 6
        tx.executeWithoutResult(s -> inventoryRepo.release(categoryId, 2));  // remaining 8

        int remaining = inventoryRepo.findById(categoryId).orElseThrow().getRemaining();
        assertEquals(8, remaining);
    }

    @Test
    void seedIfAbsent_isOneShot_doesNotResetAnExistingCounter() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID categoryId = UUID.randomUUID();

        tx.executeWithoutResult(s -> inventoryRepo.seedIfAbsent(categoryId, 10));
        tx.executeWithoutResult(s -> inventoryRepo.tryClaim(categoryId, 3)); // remaining 7
        // A second seed (e.g. a later booking re-computing the baseline) must
        // NOT clobber the live counter back to full.
        tx.executeWithoutResult(s -> inventoryRepo.seedIfAbsent(categoryId, 10));

        int remaining = inventoryRepo.findById(categoryId).orElseThrow().getRemaining();
        assertEquals(7, remaining, "seedIfAbsent must be a no-op once the row exists");
    }
}
