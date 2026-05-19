package com.innbucks.bookingservice.idempotency;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Primary
@ConditionalOnProperty(name = "app.idempotency.store", havingValue = "redis")
@Slf4j
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String STATE_RESERVED = "RESERVED";
    private static final String STATE_COMPLETED = "COMPLETED";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyEntry> get(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            Envelope envelope = mapper.readValue(json, Envelope.class);
            if (STATE_RESERVED.equals(envelope.state())) {
                return Optional.of(new IdempotencyEntry.Reserved());
            }
            if (STATE_COMPLETED.equals(envelope.state()) && envelope.response() != null) {
                return Optional.of(new IdempotencyEntry.Completed(envelope.response()));
            }
            log.warn("Unknown idempotency envelope state, treating as miss key={} state={}",
                    key, envelope.state());
            return Optional.empty();
        } catch (JsonProcessingException e) {
            // Old-format entries from a prior deploy land here. Treat as a
            // miss instead of poisoning every request that touches the key —
            // the TTL will drain them out.
            log.warn("Failed to deserialise idempotency entry, treating as miss key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean tryReserve(String key, long ttlSeconds) {
        try {
            String json = mapper.writeValueAsString(Envelope.reserved());
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key, json, Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(acquired);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise idempotency reservation for key " + key, e);
        }
    }

    @Override
    public void put(String key, StoredResponse value, long ttlSeconds) {
        try {
            String json = mapper.writeValueAsString(Envelope.completed(value));
            redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise idempotency entry for key " + key, e);
        }
    }

    @Override
    public void release(String key) {
        redis.delete(key);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Envelope(String state, StoredResponse response) {
        static Envelope reserved() {
            return new Envelope(STATE_RESERVED, null);
        }

        static Envelope completed(StoredResponse response) {
            return new Envelope(STATE_COMPLETED, response);
        }
    }
}
