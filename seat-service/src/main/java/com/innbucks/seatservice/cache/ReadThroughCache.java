package com.innbucks.seatservice.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * A thin, explicit wrapper over one Spring {@link Cache} from the read-cache
 * manager ({@link ReadCacheConfig}). Used programmatically rather than through
 * {@code @Cacheable} because every caller here has a rule an annotation cannot
 * express: a circuit-breaker fallback must never be cached, an owner must
 * bypass the cache, a value must be an immutable snapshot rather than the DTO
 * a caller is about to mutate.
 *
 * <p><b>Invalidation runs twice: now, and again after the surrounding
 * transaction commits.</b> The first clears what this pod holds immediately;
 * the second closes the window in which a concurrent reader loads the
 * pre-commit row and puts it back after the first eviction. Outside a
 * transaction there is only the first. Neither reaches another replica — the
 * TTL is the cross-pod bound (CLAUDE.md, "Caching").
 */
public final class ReadThroughCache {

    private final Cache cache;

    private ReadThroughCache(Cache cache) {
        this.cache = cache;
    }

    /** The named cache, or a pass-through when the manager does not hold it
     *  (caching switched off, or that cache's TTL/size configured to zero). */
    public static ReadThroughCache of(CacheManager manager, String name) {
        Cache cache = manager == null ? null : manager.getCache(name);
        return new ReadThroughCache(cache != null ? cache : new NoOpCache(name));
    }

    /** A pass-through cache: every read loads, nothing is kept. */
    public static ReadThroughCache disabled(String name) {
        return new ReadThroughCache(new NoOpCache(name));
    }

    public String name() {
        return cache.getName();
    }

    /**
     * The cached value for {@code key}, loading and storing it on a miss. The
     * load runs at most once per key at a time on this pod. An exception from
     * the loader is rethrown as itself (never wrapped) and nothing is stored.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Supplier<T> loader) {
        try {
            return (T) cache.get(key, loader::get);
        } catch (Cache.ValueRetrievalException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** The stored entry, or null on a miss. A hit may wrap a null value
     *  (a cached "known absent"). */
    public Cache.ValueWrapper lookup(Object key) {
        return cache.get(key);
    }

    public void put(Object key, Object value) {
        cache.put(key, value);
    }

    /** Evicts one key now and, inside a transaction, again after commit. */
    public void evict(Object key) {
        cache.evictIfPresent(key);
        afterCommit(() -> cache.evictIfPresent(key));
    }

    /** Clears every entry now and, inside a transaction, again after commit. */
    public void invalidateAll() {
        cache.invalidate();
        afterCommit(cache::invalidate);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
