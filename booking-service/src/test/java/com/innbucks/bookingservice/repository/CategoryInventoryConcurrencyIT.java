package com.innbucks.bookingservice.repository;

import com.innbucks.bookingservice.service.StockLedger;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 *
 * <p>And the lock ORDER, through the whole {@code POST /bookings} path: two
 * bookings that name the same two categories in opposite orders take the two
 * counter rows in one canonical order ({@link StockLedger}), so they queue
 * instead of deadlocking. Before, such a burst could deadlock, and
 * Postgres' abort reached the buyer as a 500.
 */
class CategoryInventoryConcurrencyIT extends BookingStockPostgresTestBase {

    @Autowired
    private CategoryInventoryRepository inventoryRepo;

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

    @Test
    void oppositeOrderBookings_x200_queueInTheCanonicalOrder_neverDeadlock_neverA500() throws Exception {
        UUID eventId = UUID.randomUUID();
        // Same random tail, top bit clear vs set: Postgres sorts a before b,
        // UUID.compareTo sorts b before a. Only an order that agrees with
        // Postgres everywhere keeps this pair deadlock-free once other paths
        // lock these rows from SQL.
        SecureRandom random = new SecureRandom();
        long msb = random.nextLong();
        long lsb = random.nextLong();
        UUID a = new UUID(msb & Long.MAX_VALUE, lsb);
        UUID b = new UUID(msb | Long.MIN_VALUE, lsb);
        assertThat(StockLedger.CATEGORY_ORDER.compare(a, b)).isNegative();
        category(eventId, a, "Bay 12", 1000);
        category(eventId, b, "Bay 13", 1000);
        double retriesBefore = claimRetries("succeeded") + claimRetries("failed") + claimRetries("refused");

        int bookings = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < bookings; i++) {
                UUID first = i % 2 == 0 ? a : b;
                UUID second = i % 2 == 0 ? b : a;
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                            {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                             "seats":[{"categoryId":"%s"},{"categoryId":"%s"}]}
                            """.formatted(eventId, first, second))).andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus())
                        .as(r.getResponse().getContentAsString())
                        .isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(remaining(a)).isEqualTo(1000 - bookings);
        assertThat(remaining(b)).isEqualTo(1000 - bookings);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(bookings);
        // Not one deadlock to retry: the order prevented them, the retry did
        // not paper over them.
        assertThat(claimRetries("succeeded") + claimRetries("failed") + claimRetries("refused"))
                .isEqualTo(retriesBefore);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }
}
