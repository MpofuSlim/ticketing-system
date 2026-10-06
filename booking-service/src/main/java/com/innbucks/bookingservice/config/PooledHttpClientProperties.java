package com.innbucks.bookingservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Sizing and timeouts of this service's ONE outbound HTTP connection pool
 * ({@link PooledHttpClient}). Sized for the single-node k3s cell: every
 * outbound client of the service shares it, so {@code max-total} bounds the
 * sockets the whole service can hold open at once.
 *
 * <p>{@code connect-timeout} / {@code read-timeout} are only the DEFAULTS for a
 * client that sets none of its own; every client that already had deliberate
 * timeouts (the notification and WhatsApp clients, the per-sibling
 * {@code *-service.*-timeout-ms} keys, Feign's per-client config) keeps them,
 * applied per request.
 *
 * <p>Kept identical in every service module (services share no code); change
 * them together. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "outbound-http")
public class PooledHttpClientProperties {

    /** Connections the whole service may hold open, across every host. */
    private int maxTotal = 50;

    /** Connections to any ONE host:port (route). */
    private int maxPerRoute = 20;

    /** Default TCP connect timeout for a client that sets none. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Default socket/response timeout for a client that sets none — and the
     * bound on every TLS handshake, which runs before a client's own timeouts
     * apply.
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * How long a call waits to LEASE a connection when the route or the pool is
     * full. Short on purpose: a full pool means the peer is already slow, and
     * queueing behind it only moves the latency to every other caller.
     */
    private Duration connectionRequestTimeout = Duration.ofSeconds(1);

    /**
     * Idle connections older than this are closed by the background evictor —
     * before kube-proxy or a partner's load balancer drops them silently, which
     * otherwise surfaces as a reset on the next reuse. Same 30s as the gateway's
     * {@code pool.max-idle-time}.
     */
    private Duration idleEviction = Duration.ofSeconds(30);

    /**
     * A pooled connection idle for longer than this is checked for staleness
     * before it is reused. Automatic retries are OFF, so a stale connection
     * must be caught here rather than retried.
     */
    private Duration validateAfterInactivity = Duration.ofSeconds(2);

    /** Hard upper bound on a connection's life, so a partner's DNS change is picked up. */
    private Duration timeToLive = Duration.ofMinutes(5);
}
