package com.innbucks.eventservice.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The read-cache manager: one Caffeine cache per name, each with its own TTL
 * and size, all recording stats.
 *
 * <p><b>Metrics come for free and must not be bound twice.</b> Spring Boot's
 * {@code CacheMetricsAutoConfiguration} binds every cache of every
 * {@link CacheManager} bean at startup as {@code cache_gets_total{result="hit"|"miss"}},
 * {@code cache_puts_total}, {@code cache_evictions_total} and {@code cache_size},
 * tagged {@code cache=<name>, cache_manager=read}. That is why the caches are
 * registered here, by name, before startup completes (a cache created lazily
 * later would never be bound), and why nothing here calls
 * {@code CaffeineCacheMetrics.monitor} itself — that would bind the same
 * meters a second time under a different tag set.
 *
 * <p>The LoadBalancer's own Caffeine cache manager is registered with
 * {@code autowireCandidate=false}, so it neither collides with this bean nor
 * gets bound by that registrar.
 *
 * <p>Nothing here uses {@code @EnableCaching}: callers go through
 * {@link ReadThroughCache} explicitly (see its javadoc for why).
 */
@Configuration
@EnableConfigurationProperties(ReadCacheProperties.class)
@Slf4j
public class ReadCacheConfig {

    public static final String READ_CACHE_MANAGER = "readCacheManager";

    public static final String PUBLIC_EVENT_PAGES = "public-event-pages";
    public static final String ORGANIZERS = "organizers";
    public static final String SEAT_CATEGORIES = "seat-categories";

    @Bean(READ_CACHE_MANAGER)
    public CacheManager readCacheManager(ReadCacheProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("Read caches are DISABLED (events.cache.enabled=false): every public read goes to "
                    + "Postgres and the sibling services");
            return new NoOpCacheManager();
        }
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Static: a name nobody registered is not created on the fly with no
        // TTL and no bound. ReadThroughCache treats a missing cache as off.
        manager.setCacheNames(List.of());
        register(manager, PUBLIC_EVENT_PAGES, properties.getPublicEventPages(), true);
        register(manager, ORGANIZERS, properties.getOrganizers(), false);
        register(manager, SEAT_CATEGORIES, properties.getSeatCategories(), false);
        return manager;
    }

    private static void register(CaffeineCacheManager manager, String name,
                                 ReadCacheProperties.Spec spec, boolean weighByEntries) {
        if (!spec.isActive()) {
            log.warn("Read cache '{}' is OFF (ttl={} maxSize={})", name, spec.getTtl(), spec.getMaxSize());
            return;
        }
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .expireAfterWrite(spec.getTtl())
                .recordStats();
        if (weighByEntries) {
            builder.maximumWeight(spec.getMaxSize())
                    .weigher((Object key, Object value) ->
                            value instanceof Weighted w ? Math.max(1, w.weight()) : 1);
        } else {
            builder.maximumSize(spec.getMaxSize());
        }
        manager.registerCustomCache(name, builder.build());
        log.info("Read cache '{}' on: ttl={} maxSize={}", name, spec.getTtl(), spec.getMaxSize());
    }

    /** A cached value that holds several items (a page of events), so the
     *  size bound counts items rather than entries. */
    public interface Weighted {
        int weight();
    }
}
