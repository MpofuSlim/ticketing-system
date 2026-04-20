package com.innbucks.seatservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySeatLockStoreTest {

    @Test
    void putThenGet_returnsOwnerWithinTtl() {
        InMemorySeatLockStore store = new InMemorySeatLockStore();
        store.put("key", "owner@example.com", 60);
        assertEquals("owner@example.com", store.get("key"));
    }

    @Test
    void get_returnsNullForUnknownKey() {
        assertNull(new InMemorySeatLockStore().get("missing"));
    }

    @Test
    void get_returnsNullAfterTtlExpires() {
        InMemorySeatLockStore store = new InMemorySeatLockStore();
        store.put("key", "owner@example.com", 0); // expiresAt = now
        assertNull(store.get("key"));
        // second call also null — entry should have been purged
        assertNull(store.get("key"));
    }

    @Test
    void delete_removesEntry() {
        InMemorySeatLockStore store = new InMemorySeatLockStore();
        store.put("key", "owner@example.com", 60);
        store.delete("key");
        assertNull(store.get("key"));
    }

    @Test
    void put_overwritesExistingLock() {
        InMemorySeatLockStore store = new InMemorySeatLockStore();
        store.put("key", "first@example.com", 60);
        store.put("key", "second@example.com", 60);
        assertEquals("second@example.com", store.get("key"));
    }
}
