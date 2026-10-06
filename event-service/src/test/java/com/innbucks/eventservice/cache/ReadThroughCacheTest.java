package com.innbucks.eventservice.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadThroughCacheTest {

    private static ReadThroughCache cache() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of());
        manager.registerCustomCache("c", Caffeine.newBuilder().maximumSize(10).build());
        return ReadThroughCache.of(manager, "c");
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void loadsOnce_thenServesTheStoredValue() {
        ReadThroughCache cache = cache();
        AtomicInteger loads = new AtomicInteger();
        assertEquals(1, (int) cache.get("k", loads::incrementAndGet));
        assertEquals(1, (int) cache.get("k", loads::incrementAndGet));
        assertEquals(1, loads.get());
    }

    @Test
    void aLoaderException_isRethrownAsItself_andNothingIsStored() {
        ReadThroughCache cache = cache();
        IllegalStateException boom = new IllegalStateException("db down");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.get("k", () -> { throw boom; }));
        assertSame(boom, thrown);
        assertNull(cache.lookup("k"));
        assertEquals("v", cache.get("k", () -> "v"));
    }

    @Test
    void aMissingCache_isAPassThrough() {
        CaffeineCacheManager staticManager = new CaffeineCacheManager();
        staticManager.setCacheNames(List.of());
        for (ReadThroughCache none : List.of(
                ReadThroughCache.of(staticManager, "never-registered"),
                ReadThroughCache.of(null, "no-manager"))) {
            AtomicInteger loads = new AtomicInteger();
            none.get("k", loads::incrementAndGet);
            none.get("k", loads::incrementAndGet);
            assertEquals(2, loads.get(), none.name());
        }
    }

    @Test
    void insideATransaction_invalidationRunsAgainAfterCommit() {
        ReadThroughCache cache = cache();
        cache.put("k", "old");
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidateAll();
        assertNull(cache.lookup("k"), "cleared at once");
        // A concurrent reader re-loads the pre-commit row before the commit...
        cache.put("k", "pre-commit");
        // ...and the after-commit pass removes it.
        commit();
        assertNull(cache.lookup("k"));
    }

    @Test
    void insideATransaction_evictionRunsAgainAfterCommit() {
        ReadThroughCache cache = cache();
        cache.put("k", "old");
        cache.put("other", "kept");
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("k");
        assertNull(cache.lookup("k"));
        cache.put("k", "pre-commit");
        commit();
        assertNull(cache.lookup("k"));
        assertEquals("kept", cache.lookup("other").get());
    }

    private static void commit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
    }
}
