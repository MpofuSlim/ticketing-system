package com.innbucks.bookingservice.idempotency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryIdempotencyStoreTest {

    @Test
    void putThenGet_returnsCompletedEntry() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        StoredResponse response = new StoredResponse(
                201, "application/json", "{\"ok\":true}".getBytes());

        store.put("key", response, 60);

        Optional<IdempotencyEntry> result = store.get("key");
        assertTrue(result.isPresent());
        assertInstanceOf(IdempotencyEntry.Completed.class, result.get());
        StoredResponse stored = ((IdempotencyEntry.Completed) result.get()).response();
        assertEquals(201, stored.status());
        assertArrayEquals("{\"ok\":true}".getBytes(), stored.body());
    }

    @Test
    void get_returnsEmptyForUnknownKey() {
        assertTrue(new InMemoryIdempotencyStore().get("missing").isEmpty());
    }

    @Test
    void get_returnsEmptyAfterTtlExpires() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.put("key", new StoredResponse(200, "text/plain", new byte[0]), 0);

        assertTrue(store.get("key").isEmpty());
        assertTrue(store.get("key").isEmpty());
    }

    @Test
    void tryReserve_succeedsOnEmptySlot_andBlocksDuplicate() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

        assertTrue(store.tryReserve("key", 60));
        assertFalse(store.tryReserve("key", 60), "second reservation must lose");
    }

    @Test
    void tryReserve_get_returnsReserved() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

        store.tryReserve("key", 60);

        Optional<IdempotencyEntry> result = store.get("key");
        assertTrue(result.isPresent());
        assertInstanceOf(IdempotencyEntry.Reserved.class, result.get());
    }

    @Test
    void put_afterReserve_overwritesWithCompleted() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.tryReserve("key", 60);

        store.put("key", new StoredResponse(201, "application/json", "{}".getBytes()), 60);

        Optional<IdempotencyEntry> result = store.get("key");
        assertTrue(result.isPresent());
        assertInstanceOf(IdempotencyEntry.Completed.class, result.get());
    }

    @Test
    void release_freesReservation_andLetsNewReserveSucceed() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.tryReserve("key", 60);

        store.release("key");

        assertTrue(store.get("key").isEmpty());
        assertTrue(store.tryReserve("key", 60));
    }

    @Test
    void tryReserve_succeeds_afterExpiredReservation() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.tryReserve("key", 0); // already expired

        assertTrue(store.tryReserve("key", 60));
    }
}
