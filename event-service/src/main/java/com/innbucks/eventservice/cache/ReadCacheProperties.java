package com.innbucks.eventservice.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Local (per-pod) read caches. Every TTL here is also the longest a change can
 * take to reach a client served by ANOTHER replica — eviction is local only.
 * See CLAUDE.md, "Caching: what is cached, what never is, and why".
 *
 * <p>{@code events.cache.enabled=false} ({@code EVENTS_CACHE_ENABLED}) switches
 * every cache here off; a TTL or size of zero switches off that one cache.
 */
@Getter
@Setter
@ConfigurationProperties("events.cache")
public class ReadCacheProperties {

    /** Kill switch for every read cache in event-service. */
    private boolean enabled = true;

    /** Public (anonymous-branch) event list pages: /events, /events/active,
     *  /events/search, /events/by-country. {@code maxSize} counts EVENTS held
     *  across all pages, not pages, so memory is bounded however large a page is. */
    private final Spec publicEventPages = new Spec(Duration.ofSeconds(30), 5_000);

    /** Organizer business details from user-service, per organizer uuid. */
    private final Spec organizers = new Spec(Duration.ofMinutes(5), 5_000);

    /** Seat-category layout from seat-service (name, description, price,
     *  sections), per event, served to PUBLIC event detail only. */
    private final Spec seatCategories = new Spec(Duration.ofSeconds(30), 5_000);

    @Getter
    @Setter
    public static class Spec {
        private Duration ttl;
        private long maxSize;

        public Spec() {
        }

        public Spec(Duration ttl, long maxSize) {
            this.ttl = ttl;
            this.maxSize = maxSize;
        }

        boolean isActive() {
            return ttl != null && !ttl.isNegative() && !ttl.isZero() && maxSize > 0;
        }
    }
}
