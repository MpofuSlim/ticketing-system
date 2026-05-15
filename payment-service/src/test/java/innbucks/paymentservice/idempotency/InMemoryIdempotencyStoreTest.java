package innbucks.paymentservice.idempotency;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryIdempotencyStoreTest {

    @Test
    void putThenGet_returnsStoredResponse() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        StoredResponse response = new StoredResponse(
                201, "application/json", "{\"ok\":true}".getBytes());

        store.put("key", response, 60);

        Optional<StoredResponse> result = store.get("key");
        assertTrue(result.isPresent());
        assertEquals(201, result.get().status());
        assertArrayEquals("{\"ok\":true}".getBytes(), result.get().body());
    }

    @Test
    void get_returnsEmptyForUnknownKey() {
        assertTrue(new InMemoryIdempotencyStore().get("missing").isEmpty());
    }

    @Test
    void get_returnsEmptyAfterTtlExpires() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.put("key", new StoredResponse(200, "text/plain", new byte[0]), 0);

        assertTrue(store.get("key").isEmpty());
        // A follow-up get should also miss (entry purged).
        assertTrue(store.get("key").isEmpty());
    }
}
