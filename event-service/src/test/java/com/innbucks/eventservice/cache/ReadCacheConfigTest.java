package com.innbucks.eventservice.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.metrics.CacheMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read-cache manager as the application context builds it: which caches
 * exist, the kill switches, and that hit/miss stats reach Micrometer through
 * Boot's own cache-metrics binding (so the hit ratio is in Prometheus).
 */
class ReadCacheConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReadCacheConfig.class, Registry.class)
            .withConfiguration(AutoConfigurations.of(CacheMetricsAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class Registry {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void byDefault_theThreeCachesExist_andNoOthersAreCreatedOnTheFly() {
        runner.run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            assertThat(manager).isInstanceOf(CaffeineCacheManager.class);
            assertThat(Set.copyOf(manager.getCacheNames())).containsExactlyInAnyOrder(
                    ReadCacheConfig.PUBLIC_EVENT_PAGES, ReadCacheConfig.ORGANIZERS, ReadCacheConfig.SEAT_CATEGORIES);
            assertThat(manager.getCache("anything-else")).isNull();
        });
    }

    @Test
    void hitsAndMisses_areBoundToMicrometer() {
        runner.run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            ReadThroughCache pages = ReadThroughCache.of(manager, ReadCacheConfig.PUBLIC_EVENT_PAGES);
            pages.get("k", () -> "v");
            pages.get("k", () -> "v");

            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            FunctionCounter hits = registry.find("cache.gets")
                    .tags("cache", ReadCacheConfig.PUBLIC_EVENT_PAGES, "cache.manager", "read", "result", "hit")
                    .functionCounter();
            FunctionCounter misses = registry.find("cache.gets")
                    .tags("cache", ReadCacheConfig.PUBLIC_EVENT_PAGES, "result", "miss")
                    .functionCounter();
            assertThat(hits).isNotNull();
            assertThat(misses).isNotNull();
            assertThat(hits.count()).isEqualTo(1.0);
            assertThat(misses.count()).isEqualTo(1.0);
            assertThat(registry.find("cache.gets").tags("cache", ReadCacheConfig.ORGANIZERS).functionCounters())
                    .isNotEmpty();
            assertThat(registry.find("cache.gets").tags("cache", ReadCacheConfig.SEAT_CATEGORIES).functionCounters())
                    .isNotEmpty();
        });
    }

    @Test
    void theKillSwitch_turnsEveryCacheIntoAPassThrough() {
        runner.withPropertyValues("events.cache.enabled=false").run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            assertThat(manager).isInstanceOf(NoOpCacheManager.class);
            AtomicInteger loads = new AtomicInteger();
            ReadThroughCache pages = ReadThroughCache.of(manager, ReadCacheConfig.PUBLIC_EVENT_PAGES);
            pages.get("k", loads::incrementAndGet);
            pages.get("k", loads::incrementAndGet);
            assertThat(loads).hasValue(2);
        });
    }

    @Test
    void aZeroTtl_switchesOffThatCacheAlone() {
        runner.withPropertyValues("events.cache.organizers.ttl=0s").run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            assertThat(manager.getCacheNames()).doesNotContain(ReadCacheConfig.ORGANIZERS);
            assertThat(manager.getCacheNames()).contains(ReadCacheConfig.PUBLIC_EVENT_PAGES);
            AtomicInteger loads = new AtomicInteger();
            ReadThroughCache organizers = ReadThroughCache.of(manager, ReadCacheConfig.ORGANIZERS);
            organizers.get("k", loads::incrementAndGet);
            organizers.get("k", loads::incrementAndGet);
            assertThat(loads).hasValue(2);
        });
    }

    @Test
    void ttlsAndSizes_bindFromProperties() {
        runner.withPropertyValues(
                        "events.cache.public-event-pages.ttl=45s",
                        "events.cache.public-event-pages.max-size=10")
                .run(ctx -> {
                    ReadCacheProperties props = ctx.getBean(ReadCacheProperties.class);
                    assertThat(props.getPublicEventPages().getTtl()).hasSeconds(45);
                    assertThat(props.getPublicEventPages().getMaxSize()).isEqualTo(10);
                    // Untouched caches keep their defaults.
                    assertThat(props.getOrganizers().getTtl()).hasMinutes(5);
                    assertThat(props.getSeatCategories().getTtl()).hasSeconds(30);
                });
    }
}
