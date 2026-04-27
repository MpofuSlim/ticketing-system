package com.innbucks.bookingservice.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@Primary
@ConditionalOnProperty(name = "app.idempotency.store", havingValue = "redis")
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
            throw new IllegalStateException("Failed to deserialize idempotency entry for key " + key, e);
        }
    }

    @Override
    public void put(String key, StoredResponse value, long ttlSeconds) {
        try {
            String json = mapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotency entry for key " + key, e);
        }
    }
}
