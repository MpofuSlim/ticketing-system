package innbucks.paymentservice.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed {@link IdempotencyStore} so an Idempotency-Key replay is honored
 * even when a load balancer routes the retry to a different pod than the
 * original POST — and survives a pod restart. This closes the per-pod gap in
 * {@link InMemoryIdempotencyStore}: behind &gt; 1 replica, an in-memory cache
 * lets a retried {@code POST /payments/transfer} hit a cold pod and execute a
 * second real transfer.
 *
 * <p>Activated by {@code app.idempotency.store=redis} (set in docker-compose
 * for staging/prod). When the property is anything else the bean is absent and
 * {@link InMemoryIdempotencyStore} is used — fine for single-instance dev and
 * for tests, which don't need Redis. Shares the same Redis instance the
 * velocity counter ({@code TransferLimitService}) already uses.
 *
 * <p>The {@link StoredResponse} is JSON-serialised; its {@code byte[] body}
 * rides as Base64 (Jackson's default) so an arbitrary response payload
 * round-trips byte-for-byte.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.idempotency.store", havingValue = "redis")
@Slf4j
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredResponse> get(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, StoredResponse.class));
        } catch (JsonProcessingException e) {
            // Corrupt / old-format entry from a prior deploy: treat as a miss
            // rather than poison every replay of this key. The TTL drains it.
            log.warn("Failed to deserialise idempotency entry, treating as miss key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, StoredResponse value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            // The response already went to the client; a failed cache write
            // just means a retry re-executes (still guarded by the
            // uq_transactions_idempotency_key DB index as the final arbiter).
            log.warn("Failed to serialise idempotency entry, skipping cache write key={}", key, e);
        }
    }
}
