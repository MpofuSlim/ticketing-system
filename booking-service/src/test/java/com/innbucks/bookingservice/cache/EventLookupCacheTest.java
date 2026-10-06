package com.innbucks.bookingservice.cache;

import com.innbucks.bookingservice.dto.EventLookupDTO;
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

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EventLookupCacheTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID ORGANIZER = UUID.randomUUID();

    private static EventLookupCache cache() {
        return new EventLookupCache(new ReadCacheConfig().readCacheManager(new ReadCacheProperties()));
    }

    private static EventLookupDTO lookup(UUID eventId, UUID organizer) {
        return EventLookupDTO.builder()
                .eventId(eventId).tenantUserUuid(organizer).title("Jazz Festival").venue("HICC")
                .startDateTime(LocalDateTime.of(2026, 12, 1, 18, 0))
                .endDateTime(LocalDateTime.of(2026, 12, 1, 23, 0))
                .build();
    }

    @Test
    void aRealAnswer_isFetchedOnce_andServedAsIndependentCopies() {
        EventLookupCache cache = cache();
        AtomicInteger calls = new AtomicInteger();

        EventLookupDTO first = cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, ORGANIZER); });
        first.setTitle("defaced");
        EventLookupDTO second = cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, ORGANIZER); });

        assertThat(calls).hasValue(1);
        assertThat(second.getTitle()).isEqualTo("Jazz Festival");
        assertThat(second.getTenantUserUuid()).isEqualTo(ORGANIZER);
        assertThat(second.getVenue()).isEqualTo("HICC");
        assertThat(second.getStartDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 18, 0));
        assertThat(second.getEndDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 23, 0));
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void aFailedLookup_isNeverCached() {
        EventLookupCache cache = cache();
        AtomicInteger calls = new AtomicInteger();
        cache.get(EVENT, () -> { calls.incrementAndGet(); return null; });
        cache.get(EVENT, () -> { calls.incrementAndGet(); return null; });
        assertThat(calls).hasValue(2);
    }

    @Test
    void aLookupWithNoOrganizer_orForAnotherEvent_isNeverCached() {
        EventLookupCache cache = cache();
        AtomicInteger calls = new AtomicInteger();
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, null); });
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, null); });
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(UUID.randomUUID(), ORGANIZER); });
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(UUID.randomUUID(), ORGANIZER); });
        assertThat(calls).hasValue(4);
    }

    @Test
    void eventsAreKeptApart() {
        EventLookupCache cache = cache();
        UUID other = UUID.randomUUID();
        cache.get(EVENT, () -> lookup(EVENT, ORGANIZER));
        UUID otherOrganizer = UUID.randomUUID();
        assertThat(cache.get(other, () -> lookup(other, otherOrganizer)).getTenantUserUuid())
                .isEqualTo(otherOrganizer);
        assertThat(cache.get(EVENT, () -> null).getTenantUserUuid()).isEqualTo(ORGANIZER);
    }

    @Test
    void theKillSwitch_makesEveryLookupLive() {
        EventLookupCache cache = new EventLookupCache(new NoOpCacheManager());
        AtomicInteger calls = new AtomicInteger();
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, ORGANIZER); });
        cache.get(EVENT, () -> { calls.incrementAndGet(); return lookup(EVENT, ORGANIZER); });
        assertThat(calls).hasValue(2);
    }

    @Configuration(proxyBeanMethods = false)
    static class Registry {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void theCache_isBoundToMicrometer_andSwitchesOffByProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ReadCacheConfig.class, Registry.class)
                .withConfiguration(AutoConfigurations.of(CacheMetricsAutoConfiguration.class));
        runner.run(ctx -> {
            CacheManager manager = ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class);
            assertThat(manager.getCacheNames()).containsExactly(ReadCacheConfig.EVENT_LOOKUPS);
            assertThat(ctx.getBean(MeterRegistry.class).find("cache.gets")
                    .tags("cache", ReadCacheConfig.EVENT_LOOKUPS, "cache.manager", "read")
                    .functionCounters()).isNotEmpty();
        });
        runner.withPropertyValues("bookings.cache.enabled=false").run(ctx ->
                assertThat(ctx.getBean(ReadCacheConfig.READ_CACHE_MANAGER, CacheManager.class))
                        .isInstanceOf(NoOpCacheManager.class));
    }
}
