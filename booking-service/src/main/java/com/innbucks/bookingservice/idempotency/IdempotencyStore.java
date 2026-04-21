package com.innbucks.bookingservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay. An implementation
 * is expected to purge entries whose TTL has elapsed.
 *
 * The in-memory implementation is fine for a single instance. A
 * multi-instance deployment should swap it for a shared backend
 * (Redis) so retries landing on a different pod still hit the cache.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse value, long ttlSeconds);
}
