package com.innbucks.seatservice.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Primary
public class InMemorySeatLockStore implements SeatLockStore {

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String owner, long ttlSeconds) {
        long expiresAtEpochSeconds = Instant.now().getEpochSecond() + ttlSeconds;
        locks.put(key, new LockEntry(owner, expiresAtEpochSeconds));
    }

    @Override
    public String get(String key) {
        LockEntry entry = locks.get(key);
        if (entry == null) {
            return null;
        }

        long now = Instant.now().getEpochSecond();
        if (entry.expiresAtEpochSeconds() <= now) {
            locks.remove(key);
            return null;
        }

        return entry.owner();
    }

    @Override
    public void delete(String key) {
        locks.remove(key);
    }

    private record LockEntry(String owner, long expiresAtEpochSeconds) {
    }
}
