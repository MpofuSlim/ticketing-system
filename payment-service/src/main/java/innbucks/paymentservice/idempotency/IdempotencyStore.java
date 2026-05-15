package innbucks.paymentservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay. Implementations are
 * expected to purge entries whose TTL has elapsed.
 *
 * <p>Payment-service currently ships only the in-memory implementation.
 * Once payment-service runs &gt; 1 replica, swap to a Redis-backed impl
 * (see booking-service's RedisIdempotencyStore for the reference shape)
 * so replays survive a load-balancer hitting a different pod than the
 * original POST. Per-pod in-memory cache is enough for single-instance
 * deployment; replays across pods are not guaranteed.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse value, long ttlSeconds);
}
