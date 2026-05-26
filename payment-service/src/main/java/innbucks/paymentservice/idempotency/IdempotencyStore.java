package innbucks.paymentservice.idempotency;

import java.util.Optional;

/**
 * Keyed response cache for Idempotency-Key replay. Implementations are
 * expected to purge entries whose TTL has elapsed.
 *
 * <p>Two implementations ship: {@link InMemoryIdempotencyStore} (per-pod
 * {@code ConcurrentHashMap}, the default — fine for single-instance dev and
 * tests) and {@link RedisIdempotencyStore} (shared across pods, activated by
 * {@code app.idempotency.store=redis}). Run the Redis store under more than one
 * replica: with the in-memory store a load balancer routing a retry to a
 * different pod — or a pod restart — would miss the cache and let the request
 * execute a second time.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> get(String key);

    void put(String key, StoredResponse value, long ttlSeconds);
}
