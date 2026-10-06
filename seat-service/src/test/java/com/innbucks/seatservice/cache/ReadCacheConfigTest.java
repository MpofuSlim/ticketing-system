package com.innbucks.seatservice.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.cache.autoconfigure.metrics.CacheMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

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
    void theLayoutCache_existsAndIsBoundToMicrometer() {
        runner.run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            assertThat(manager.getCacheNames()).containsExactly(ReadCacheConfig.CATEGORY_LAYOUT);
            assertThat(manager.getCache("anything-else")).isNull();
            ReadThroughCache.of(manager, ReadCacheConfig.CATEGORY_LAYOUT).get("k", () -> "v");
            ReadThroughCache.of(manager, ReadCacheConfig.CATEGORY_LAYOUT).get("k", () -> "v");
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find("cache.gets")
                    .tags("cache", ReadCacheConfig.CATEGORY_LAYOUT, "cache.manager", "read", "result", "hit")
                    .functionCounter().count()).isEqualTo(1.0);
        });
    }

    @Test
    void theKillSwitch_andAZeroTtl_turnItOff() {
        runner.withPropertyValues("seats.cache.enabled=false").run(ctx ->
                assertThat(ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class))
                        .isInstanceOf(NoOpCacheManager.class));
        runner.withPropertyValues("seats.cache.category-layout.ttl=0s").run(ctx ->
                assertThat(ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class).getCacheNames())
                        .isEmpty());
    }
}
