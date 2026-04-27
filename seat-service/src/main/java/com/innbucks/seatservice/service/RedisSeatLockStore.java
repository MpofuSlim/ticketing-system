package com.innbucks.seatservice.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Primary
@ConditionalOnProperty(name = "app.lock.store", havingValue = "redis")
public class RedisSeatLockStore implements SeatLockStore {

    private final StringRedisTemplate redis;

    public RedisSeatLockStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(String key, String owner, long ttlSeconds) {
        redis.opsForValue().set(key, owner, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void delete(String key) {
        redis.delete(key);
    }
}
