package com.innbucks.seatservice.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Local (per-pod) read caches. Every TTL here is also the longest a change can
 * take to reach a client served by ANOTHER replica — eviction is local only.
 * See CLAUDE.md, "Caching: what is cached, what never is, and why".
 *
 * <p>{@code seats.cache.enabled=false} ({@code SEATS_CACHE_ENABLED}) switches
 * every cache here off; a TTL or size of zero switches off that one cache.
 */
@Getter
@Setter
@ConfigurationProperties("seats.cache")
public class ReadCacheProperties {

    /** Kill switch for every read cache in seat-service. */
    private boolean enabled = true;

    /** Static category layout per event behind {@code GET /seat-categories?eventId=}
     *  (name, description, price, sections). Never availability. */
    private final Spec categoryLayout = new Spec(Duration.ofSeconds(60), 5_000);

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
