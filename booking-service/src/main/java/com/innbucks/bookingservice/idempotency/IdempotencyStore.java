package com.innbucks.bookingservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay. An implementation
 * is expected to purge entries whose TTL has elapsed. Two implementations
 * exist: an in-memory map for local dev/tests, and a Redis-backed store
 * (selected via app.idempotency.store=redis) so replicas share state.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse value, long ttlSeconds);
}
