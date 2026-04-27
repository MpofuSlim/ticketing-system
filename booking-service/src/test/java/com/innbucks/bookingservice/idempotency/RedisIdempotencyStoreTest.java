package com.innbucks.bookingservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIdempotencyStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void put_serializesAndWritesWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        StoredResponse stored = new StoredResponse(201, "application/json",
                "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        new RedisIdempotencyStore(redis, mapper).put("idem:abc", stored, 600);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("idem:abc"), json.capture(), ttl.capture());
        assertEquals(600, ttl.getValue().getSeconds());
        assertTrue(json.getValue().contains("\"status\":201"));
        assertTrue(json.getValue().contains("\"contentType\":\"application/json\""));
    }

    @Test
    void get_deserializesStoredResponse() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        StoredResponse original = new StoredResponse(200, "text/plain",
                "ok".getBytes(StandardCharsets.UTF_8));
        when(ops.get("idem:abc")).thenReturn(mapper.writeValueAsString(original));

        Optional<StoredResponse> result = new RedisIdempotencyStore(redis, mapper).get("idem:abc");

        assertTrue(result.isPresent());
        assertEquals(200, result.get().status());
        assertEquals("text/plain", result.get().contentType());
        assertArrayEquals(original.body(), result.get().body());
    }

    @Test
    void get_returnsEmptyForMissingKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("idem:missing")).thenReturn(null);

        assertTrue(new RedisIdempotencyStore(redis, mapper).get("idem:missing").isEmpty());
    }
}
