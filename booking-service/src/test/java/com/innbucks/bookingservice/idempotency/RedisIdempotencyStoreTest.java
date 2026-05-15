package com.innbucks.bookingservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisIdempotencyStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> wireOps(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    @Test
    void put_serializesCompletedEnvelopeWithTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);

        StoredResponse stored = new StoredResponse(201, "application/json",
                "{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        new RedisIdempotencyStore(redis, mapper).put("idem:abc", stored, 600);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(eq("idem:abc"), json.capture(), ttl.capture());
        assertEquals(600, ttl.getValue().getSeconds());
        assertTrue(json.getValue().contains("\"state\":\"COMPLETED\""));
        assertTrue(json.getValue().contains("\"status\":201"));
    }

    @Test
    void get_returnsCompleted_whenEnvelopeStateIsCompleted() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);

        StoredResponse original = new StoredResponse(200, "text/plain",
                "ok".getBytes(StandardCharsets.UTF_8));
        when(ops.get("idem:abc")).thenReturn(
                mapper.writeValueAsString(RedisIdempotencyStore.Envelope.completed(original)));

        Optional<IdempotencyEntry> result = new RedisIdempotencyStore(redis, mapper).get("idem:abc");

        assertTrue(result.isPresent());
        assertInstanceOf(IdempotencyEntry.Completed.class, result.get());
        StoredResponse got = ((IdempotencyEntry.Completed) result.get()).response();
        assertEquals(200, got.status());
        assertArrayEquals(original.body(), got.body());
    }

    @Test
    void get_returnsReserved_whenEnvelopeStateIsReserved() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);
        when(ops.get("idem:abc")).thenReturn(
                mapper.writeValueAsString(RedisIdempotencyStore.Envelope.reserved()));

        Optional<IdempotencyEntry> result = new RedisIdempotencyStore(redis, mapper).get("idem:abc");

        assertTrue(result.isPresent());
        assertInstanceOf(IdempotencyEntry.Reserved.class, result.get());
    }

    @Test
    void get_returnsEmptyForMissingKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);
        when(ops.get("idem:missing")).thenReturn(null);

        assertTrue(new RedisIdempotencyStore(redis, mapper).get("idem:missing").isEmpty());
    }

    @Test
    void get_returnsEmptyOnDeserialisationFailure_legacyOrCorruptValue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);
        when(ops.get("idem:legacy")).thenReturn("{not-valid-json");

        // Must not throw — old-format / corrupt entries are treated as misses
        // so a single bad value doesn't poison every request that touches it.
        assertTrue(new RedisIdempotencyStore(redis, mapper).get("idem:legacy").isEmpty());
    }

    @Test
    void tryReserve_callsSetIfAbsent_withReservedEnvelope() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        boolean acquired = new RedisIdempotencyStore(redis, mapper).tryReserve("idem:abc", 30);

        assertTrue(acquired);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(ops).setIfAbsent(eq("idem:abc"), json.capture(), ttl.capture());
        assertEquals(30, ttl.getValue().getSeconds());
        assertTrue(json.getValue().contains("\"state\":\"RESERVED\""));
    }

    @Test
    void tryReserve_returnsFalse_whenRedisReportsNotAcquired() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = wireOps(redis);
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        assertFalse(new RedisIdempotencyStore(redis, mapper).tryReserve("idem:abc", 30));
    }

    @Test
    void release_callsDelete() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        new RedisIdempotencyStore(redis, mapper).release("idem:abc");

        verify(redis).delete("idem:abc");
    }
}
