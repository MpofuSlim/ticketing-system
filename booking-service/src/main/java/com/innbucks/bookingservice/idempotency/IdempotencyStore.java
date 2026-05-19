package com.innbucks.bookingservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay with reservation support.
 *
 * <p>The filter calls {@link #get} first. On miss it calls
 * {@link #tryReserve} to atomically claim the slot — concurrent callers
 * with the same key lose the race and are rejected with a 409 instead of
 * both executing the operation. On a 2xx response the reservation is
 * overwritten by {@link #put}; on non-2xx or exception the reservation
 * is freed via {@link #release} so a client retry can proceed.
 *
 * <p>An implementation is expected to honour TTLs on both the reservation
 * (short) and the completed entry (longer, but capped).
 */
public interface IdempotencyStore {

    Optional<IdempotencyEntry> get(String key);

    /** Atomic SET-NX. Returns true iff the slot was empty and is now reserved. */
    boolean tryReserve(String key, long ttlSeconds);

    /** Overwrites whatever is at {@code key} with a completed-response entry. */
    void put(String key, StoredResponse value, long ttlSeconds);

    /** Removes the entry at {@code key}; safe to call when nothing is stored. */
    void release(String key);
}
