package com.innbucks.apigateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.NetUtil;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.web.server.ServerWebExchange;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
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
 * <p><b>An IPv6 client is keyed by its /64</b> ({@link #bucketAddress}), not its
 * full address. Cloudflare reaches IPv6 clients by default, so
 * {@code CF-Connecting-IP} is often the client's own v6 address, and a /64 is
 * what one subscriber holds: a VPS or a phone with a routed /64 can send every
 * request from a fresh address in it. Keyed per address, that is a new bucket
 * per request, the XFF rotation this class exists to stop. A /64 is also the
 * smallest prefix a network normally hands one subscriber, so it seldom merges
 * two of them (a host that gives each VPS one address of a shared /64 does,
 * like a NAT on IPv4). A /48 holder still gets 65,536 buckets; a coarser prefix
 * would merge carrier customers who each hold a /64 of one pool. IPv4 is keyed
 * per address, as before.
 *
 * <p><b>Fallback.</b> A missing header (local dev, a curl to the NodePort from
 * the box) or a value that is not one literal falls back to the exchange's
 * remote address. Behind nginx that is the transformer's unresolved XFF
 * address, which the key resolvers turn into their shared fallback bucket: an
 * nginx that has not been updated degrades to exactly the behaviour before this
 * class, never to a caller-chosen key. Behind an updated nginx neither case
 * happens, so {@code gateway.client_ip.fallback{reason="absent"|"invalid"}}
 * staying flat is the check that the header arrives (alert
 * {@code GatewayClientIpFallback}).
 *
 * <p><b>The same value twice is one value.</b> nginx never de-duplicates
 * {@code proxy_set_header}, so a location that also does {@code include
 * proxy_params} (Ubuntu's sets {@code X-Real-IP $remote_addr} too) sends the
 * header twice with one value. Both lines are nginx's: a {@code proxy_set_header}
 * stops nginx passing on the caller's header of that name. So equal values are
 * the client; values that disagree are not one client, and count as invalid.
 */
public class ClientIpResolver implements RemoteAddressResolver {

    /** Set by the host nginx from {@code $remote_addr}; never by the caller. */
    public static final String CLIENT_IP_HEADER = "X-Real-IP";

    /** The prefix an IPv6 client is keyed by: one subscriber's network. */
    static final int IPV6_KEY_PREFIX_BITS = 64;

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
        InetAddress address = oneLiteral(values);
        if (address == null) {
            invalid.increment();
            return exchange.getRequest().getRemoteAddress();
        }
        return new InetSocketAddress(address, 0);
    }

    /**
     * What a rate-limit bucket is keyed on for {@code address}: an IPv4 address
     * whole, an IPv6 address as its /64 network ({@code 2001:db8:1:2:0:0:0:0/64}).
     * Every IP key in {@link RateLimiterConfig} goes through here.
     */
    public static String bucketAddress(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        byte[] network = address.getAddress();
        Arrays.fill(network, IPV6_KEY_PREFIX_BITS / 8, network.length, (byte) 0);
        return fromBytes(network).getHostAddress() + "/" + IPV6_KEY_PREFIX_BITS;
    }

    /**
     * The address every value names, or {@code null} when one of them is not a
     * literal or two of them disagree.
     */
    static InetAddress oneLiteral(List<String> values) {
        InetAddress address = null;
        for (String value : values) {
            InetAddress parsed = parseLiteral(value);
            if (parsed == null || (address != null && !address.equals(parsed))) {
                return null;
            }
            address = parsed;
        }
        return address;
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
        return bytes == null ? null : fromBytes(bytes);
    }

    /**
     * A 4- or 16-byte array as an address, never looked up; a mapped
     * {@code ::ffff:a.b.c.d} comes back as its Inet4Address.
     */
    private static InetAddress fromBytes(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException impossibleForAValidLength) {
            throw new IllegalArgumentException("not an address length: " + bytes.length);
        }
    }

    private static Counter fallbackCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(FALLBACK_METRIC)
                .description("Rate-limit keys that could not use the nginx-set X-Real-IP")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
