package innbucks.paymentservice.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisIdempotencyStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> stubOps(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    @Test
    void put_writesJsonWithTtl_andGet_roundTripsIncludingBinaryBody() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = stubOps(redis);
        RedisIdempotencyStore store = new RedisIdempotencyStore(redis, mapper);

        byte[] body = "{\"transactionID\":\"1155\"}".getBytes(StandardCharsets.UTF_8);
        StoredResponse response = new StoredResponse(200, "application/json", body, "sha-abc");

        // Capture what the store writes, then feed it back on read — proves the
        // value round-trips byte-for-byte through the store's own (de)serialisation,
        // including the byte[] body that Jackson carries as Base64.
        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        store.put("POST /payments/transfer#k1", response, 3600);
        verify(ops).set(eq("POST /payments/transfer#k1"), written.capture(), eq(Duration.ofSeconds(3600)));

        when(ops.get("POST /payments/transfer#k1")).thenReturn(written.getValue());
        Optional<StoredResponse> read = store.get("POST /payments/transfer#k1");

        assertTrue(read.isPresent());
        assertEquals(200, read.get().status());
        assertEquals("application/json", read.get().contentType());
        assertEquals("sha-abc", read.get().bodySha256());
        assertArrayEquals(body, read.get().body(), "binary body must survive the JSON/Base64 round-trip");
    }

    @Test
    void get_returnsEmpty_onCacheMiss() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = stubOps(redis);
        when(ops.get("missing")).thenReturn(null);

        assertTrue(new RedisIdempotencyStore(redis, mapper).get("missing").isEmpty());
    }

    @Test
    void get_treatsCorruptEntryAsMiss_insteadOfThrowing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = stubOps(redis);
        when(ops.get("corrupt")).thenReturn("not-json{");

        assertTrue(new RedisIdempotencyStore(redis, mapper).get("corrupt").isEmpty(),
                "a corrupt/old-format entry must degrade to a miss, not poison the request");
    }
}
