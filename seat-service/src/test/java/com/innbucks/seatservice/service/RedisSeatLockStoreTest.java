package com.innbucks.seatservice.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSeatLockStoreTest {

    @Test
    void put_writesValueWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        new RedisSeatLockStore(redis).put("seat:lock:1", "owner@example.com", 60);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eqArg("seat:lock:1"), eqArg("owner@example.com"), ttl.capture());
        assertEquals(60, ttl.getValue().getSeconds());
    }

    @Test
    void get_returnsValueFromRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("seat:lock:1")).thenReturn("owner@example.com");

        assertEquals("owner@example.com", new RedisSeatLockStore(redis).get("seat:lock:1"));
    }

    @Test
    void delete_callsRedisDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        new RedisSeatLockStore(redis).delete("seat:lock:1");

        verify(redis).delete("seat:lock:1");
    }

    private static <T> T eqArg(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
