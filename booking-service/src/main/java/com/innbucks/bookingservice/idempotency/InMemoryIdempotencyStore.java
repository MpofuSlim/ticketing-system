package com.innbucks.bookingservice.idempotency;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyEntry> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtEpochSeconds() <= Instant.now().getEpochSecond()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.payload());
    }

    @Override
    public boolean tryReserve(String key, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        Entry reservation = new Entry(new IdempotencyEntry.Reserved(), now + ttlSeconds);
        Entry existing = entries.compute(key, (k, current) -> {
            if (current == null || current.expiresAtEpochSeconds() <= now) {
                return reservation;
            }
            return current;
        });
        return existing == reservation;
    }

    @Override
    public void put(String key, StoredResponse value, long ttlSeconds) {
        entries.put(key, new Entry(
                new IdempotencyEntry.Completed(value),
                Instant.now().getEpochSecond() + ttlSeconds
        ));
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    private record Entry(IdempotencyEntry payload, long expiresAtEpochSeconds) {
    }
}
