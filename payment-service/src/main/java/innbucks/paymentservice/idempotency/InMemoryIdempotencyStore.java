package innbucks.paymentservice.idempotency;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResponse> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtEpochSeconds() <= Instant.now().getEpochSecond()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public void put(String key, StoredResponse value, long ttlSeconds) {
        entries.put(key, new Entry(value, Instant.now().getEpochSecond() + ttlSeconds));
    }

    private record Entry(StoredResponse response, long expiresAtEpochSeconds) {
    }
}
