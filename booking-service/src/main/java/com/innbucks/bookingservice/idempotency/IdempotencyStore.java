package com.innbucks.bookingservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay. An implementation
 * is expected to purge entries whose TTL has elapsed. Backed by the
 * in-memory store; deployments are pinned to a single replica.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse value, long ttlSeconds);
}
