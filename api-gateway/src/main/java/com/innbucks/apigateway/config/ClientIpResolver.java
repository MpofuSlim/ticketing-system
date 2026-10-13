package com.innbucks.apigateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.NetUtil;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The caller's address as the host nginx saw it: the one input every IP-keyed
 * rate limit in the gateway keys on (bean {@code gatewayRemoteAddressResolver}
 * in {@link RateLimiterConfig}).
 *
 * <p><b>Why not X-Forwarded-For.</b> The gateway runs
 * {@code server.forward-headers-strategy: framework}, so Spring's
 * {@code ForwardedHeaderTransformer} replaces the request's remote address with
 * the LEFT-MOST {@code X-Forwarded-For} entry and then strips the header.
 * Cloudflare appends to whatever XFF the caller sent, so that entry is the
 * caller's choice. The transformer also builds it UNRESOLVED, so the previous
 * {@code XForwardedRemoteAddressResolver} found no header left to read and every
 * request carrying any XFF fell into its key resolver's one shared fallback
 * bucket: one quota for the whole internet, which one caller could drain for
 * everyone. {@code gateway.trusted-proxy-count} configured a header that was
 * already gone, so it was removed with it.
 *
 * <p><b>Why X-Real-IP can be trusted.</b> nginx takes the client address from
 * {@code CF-Connecting-IP} only when the TCP peer is a Cloudflare range
 * ({@code real_ip}), then sends it as {@code X-Real-IP}, OVERWRITING any value
 * the caller supplied ({@code deploy/k8s/README.md} §3). That holds only while
 * nothing but that nginx can reach the gateway, so NodePort 30080 must be
 * reachable from the host alone. It also means nginx is updated BEFORE this
 * gateway is deployed: until nginx overwrites the header, a caller picks it.
 *
 * <p><b>A literal, never a lookup.</b> The value is accepted only as an IPv4 or
 * IPv6 literal (Netty's {@link NetUtil}) and turned into an address from its
 * bytes. {@code InetAddress.getByName} is never called on it: for a hostname, or
 * a legacy form such as {@code 127.1}, it would ask the system resolver, which
 * puts a caller-chosen DNS lookup on the request path. Building from bytes also
 * normalises the key: {@code 2001:db8::1} and {@code 2001:0db8:0:0::1} share a
 * bucket, and an IPv4-mapped IPv6 address keys as its IPv4 form.
 *
 * <p><b>Fallback.</b> A missing header (local dev, a curl to the NodePort from
 * the box) or anything that is not exactly one literal falls back to the
 * exchange's remote address. Behind nginx that is the transformer's unresolved
 * XFF address, which the key resolvers turn into their shared fallback bucket:
 * an nginx that has not been updated degrades to exactly the behaviour before
 * this class, never to a caller-chosen key. Behind an updated nginx neither case
 * happens, so {@code gateway.client_ip.fallback{reason="absent"|"invalid"}}
 * staying flat is the check that the header arrives.
 */
public class ClientIpResolver implements RemoteAddressResolver {

    /** Set by the host nginx from {@code $remote_addr}; never by the caller. */
    public static final String CLIENT_IP_HEADER = "X-Real-IP";

    static final String FALLBACK_METRIC = "gateway.client_ip.fallback";

    private final Counter absent;
    private final Counter invalid;

    public ClientIpResolver(MeterRegistry meterRegistry) {
        // Registered at startup, so increase() sees the first fallback.
        this.absent = fallbackCounter(meterRegistry, "absent");
        this.invalid = fallbackCounter(meterRegistry, "invalid");
    }

    @Override
    public InetSocketAddress resolve(ServerWebExchange exchange) {
        List<String> values = exchange.getRequest().getHeaders().get(CLIENT_IP_HEADER);
        if (values == null || values.isEmpty()) {
            absent.increment();
            return exchange.getRequest().getRemoteAddress();
        }
        // nginx sends exactly one value; two means it was not nginx.
        InetAddress address = values.size() == 1 ? parseLiteral(values.get(0)) : null;
        if (address == null) {
            invalid.increment();
            return exchange.getRequest().getRemoteAddress();
        }
        return new InetSocketAddress(address, 0);
    }

    /**
     * {@code value} as an IPv4 or IPv6 literal, or {@code null} for anything
     * else. Never resolves a name.
     */
    static InetAddress parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        if (bytes == null) {
            return null;
        }
        try {
            // A 4- or 16-byte array is never looked up; a mapped
            // ::ffff:a.b.c.d comes back as its Inet4Address.
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossibleForAValidLength) {
            return null;
        }
    }

    private static Counter fallbackCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(FALLBACK_METRIC)
                .description("Rate-limit keys that could not use the nginx-set X-Real-IP")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
