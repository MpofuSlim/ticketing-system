package com.innbucks.apigateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every IP-keyed rate limit keys on the X-Real-IP the host nginx sets, never on
 * X-Forwarded-For, and never resolves a name to get there.
 *
 * <p>Each request is built the way it reaches the key resolvers in production:
 * the TCP peer is nginx, and Spring's {@link ForwardedHeaderTransformer} (what
 * {@code server.forward-headers-strategy: framework} installs) has already
 * rewritten the remote address from the left-most XFF entry and stripped the
 * header. {@code GatewayClientIpTest} drives the same cases through the running
 * gateway.
 */
class ClientIpResolverTest {

    /** The pod's view of the host nginx: the socket peer, never the caller. */
    private static final InetSocketAddress NGINX_PEER = new InetSocketAddress("10.42.0.1", 51000);

    /** What Cloudflare appends to the caller's own XFF. */
    private static final String CLOUDFLARE_EDGE = "172.70.1.1";

    private SimpleMeterRegistry meters;
    private ClientIpResolver resolver;
    private RateLimiterConfig config;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        resolver = new ClientIpResolver(meters);
        config = new RateLimiterConfig();
    }

    /** Each IP-keyed resolver, with the shared key it falls back to. */
    static Stream<Arguments> ipKeyedResolvers() {
        return Stream.of(
                Arguments.of("gatewayKeyResolver", "anonymous"),
                Arguments.of("preAuthIpKeyResolver", "anonymous-pre-auth"),
                Arguments.of("paymentsInnbucksIpKeyResolver", "anonymous-payments-innbucks"));
    }

    // ------------------------------------------------------------ the header wins

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void aSpoofedLeftMostXff_withTheSameRealIp_keepsTheKey(String name, String fallbackKey) {
        KeyResolver keys = keyResolver(name);

        String plain = keys.resolve(edge("203.0.113.7", null)).block();
        String spoofedOnce = keys.resolve(edge("203.0.113.7", "6.6.6.6, " + CLOUDFLARE_EDGE)).block();
        String spoofedAgain = keys.resolve(edge("203.0.113.7", "7.7.7.7, 8.8.8.8, " + CLOUDFLARE_EDGE)).block();

        assertThat(plain).isEqualTo("ip:203.0.113.7");
        assertThat(spoofedOnce).isEqualTo(plain);
        assertThat(spoofedAgain).isEqualTo(plain);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void aDifferentRealIp_changesTheKey(String name, String fallbackKey) {
        KeyResolver keys = keyResolver(name);
        String xff = "6.6.6.6, " + CLOUDFLARE_EDGE;

        assertThat(keys.resolve(edge("203.0.113.7", xff)).block()).isEqualTo("ip:203.0.113.7");
        assertThat(keys.resolve(edge("198.51.100.20", xff)).block()).isEqualTo("ip:198.51.100.20");
    }

    @Test
    void anIpv6Client_isKeyedByItsNetwork_notByHowItIsSpelled() {
        KeyResolver keys = keyResolver("preAuthIpKeyResolver");

        String compressed = keys.resolve(edge("2001:db8::1", null)).block();

        assertThat(compressed).isEqualTo("ip:2001:db8:0:0:0:0:0:0/64");
        assertThat(keys.resolve(edge("2001:0DB8:0:0:0:0:0:1", null)).block()).isEqualTo(compressed);
        assertThat(keys.resolve(edge("[2001:db8::1]", null)).block()).isEqualTo(compressed);
        // An IPv4-mapped address is the IPv4 client, one bucket, keyed whole.
        assertThat(keys.resolve(edge("::ffff:203.0.113.7", null)).block()).isEqualTo("ip:203.0.113.7");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void rotatingAddressesInsideOneSlash64_staysInOneBucket(String name, String fallbackKey) {
        // A VPS or a phone with a routed /64 can send every request from a
        // fresh address in it; per-address keys would be a bucket per request.
        KeyResolver keys = keyResolver(name);

        String first = keys.resolve(edge("2001:db8:1:2::1", null)).block();

        assertThat(first).isEqualTo("ip:2001:db8:1:2:0:0:0:0/64");
        assertThat(keys.resolve(edge("2001:db8:1:2:a1b2:c3d4:e5f6:789", null)).block()).isEqualTo(first);
        assertThat(keys.resolve(edge("2001:db8:1:2:ffff:ffff:ffff:ffff", null)).block()).isEqualTo(first);
        assertThat(keys.resolve(edge("2001:db8:1:2:8000::", "6.6.6.6, " + CLOUDFLARE_EDGE)).block())
                .isEqualTo(first);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void anotherSlash64_isAnotherBucket(String name, String fallbackKey) {
        KeyResolver keys = keyResolver(name);

        assertThat(keys.resolve(edge("2001:db8:1:3::1", null)).block()).isEqualTo("ip:2001:db8:1:3:0:0:0:0/64");
        assertThat(keys.resolve(edge("2001:db8:1:2::1", null)).block()).isEqualTo("ip:2001:db8:1:2:0:0:0:0/64");
        // One bit above the /64 boundary.
        assertThat(keys.resolve(edge("2001:db8:1:2::1", null)).block())
                .isNotEqualTo(keys.resolve(edge("2001:db8:1:1::1", null)).block());
    }

    @Test
    void bucketAddress_keepsIpv4Whole_andCutsIpv6ToItsSlash64() throws Exception {
        assertThat(ClientIpResolver.bucketAddress(InetAddress.getByName("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.bucketAddress(InetAddress.getByName("2001:db8:aa:bb:cc:dd:ee:ff")))
                .isEqualTo("2001:db8:aa:bb:0:0:0:0/64");
        // A scoped address (a local dev socket) is cut the same way.
        assertThat(ClientIpResolver.bucketAddress(InetAddress.getByName("fe80::1%1")))
                .isEqualTo("fe80:0:0:0:0:0:0:0/64");
    }

    @Test
    void anIpv6SocketPeer_isKeyedByItsSlash64_too() {
        // Local dev on an IPv6 loopback or LAN: the socket-peer fallback goes
        // through the same key rule as the header.
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/otp/send")
                .remoteAddress(new InetSocketAddress("2001:db8:1:2::9", 51000))
                .build();

        assertThat(keyResolver("preAuthIpKeyResolver").resolve(transformed(request)).block())
                .isEqualTo("ip:2001:db8:1:2:0:0:0:0/64");
    }

    // ------------------------------------------------------------ fallbacks

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost",              // a lookup would answer 127.0.0.1 or ::1
            "attacker.example.com",
            "127.1",                  // InetAddress.getByName reads this as 127.0.0.1
            "0x7f.0.0.1",
            "0177.0.0.1",
            "203.0.113",
            "256.1.1.1",
            "203.0.113.7, 198.51.100.20",
            " 203.0.113.7",
            "203.0.113.7:443",
            "",
            "unknown"})
    void aHostnameOrMalformedRealIp_fallsBack_withoutALookup(String value) {
        // No XFF: the fallback is the socket peer, so a key naming anything
        // but 10.42.0.1 means the value was resolved or read some other way.
        ServerWebExchange exchange = edge(value, null);

        assertThat(resolver.resolve(exchange)).isEqualTo(NGINX_PEER);
        assertThat(keyResolver("preAuthIpKeyResolver").resolve(edge(value, null)).block())
                .isEqualTo("ip:10.42.0.1");
        assertThat(fallbacks("invalid")).isEqualTo(2.0);
        assertThat(fallbacks("absent")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void aHostnameRealIp_behindXff_landsInTheSharedBucket(String name, String fallbackKey) {
        // The fallback here is the transformer's UNRESOLVED XFF address, which
        // must not become a key: neither ip:6.6.6.6 nor a looked-up hostname.
        String key = keyResolver(name).resolve(edge("localhost", "6.6.6.6, " + CLOUDFLARE_EDGE)).block();

        assertThat(key).isEqualTo(fallbackKey);
    }

    @Test
    void twoRealIpValuesThatDisagree_areNotOneClient_andFallBack() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/auth/otp/send")
                .remoteAddress(NGINX_PEER)
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7", "198.51.100.20")
                .build();

        assertThat(resolver.resolve(transformed(request))).isEqualTo(NGINX_PEER);
        assertThat(fallbacks("invalid")).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void theSameRealIpTwice_isOneClient(String name, String fallbackKey) {
        // A location that does `include proxy_params` AND sets X-Real-IP itself:
        // nginx sends both lines, each $remote_addr. Not a fallback.
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/otp/send")
                .remoteAddress(NGINX_PEER)
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7", "203.0.113.7")
                .header("X-Forwarded-For", "6.6.6.6, " + CLOUDFLARE_EDGE + ", 203.0.113.7", "203.0.113.7")
                .build();

        assertThat(keyResolver(name).resolve(transformed(request)).block()).isEqualTo("ip:203.0.113.7");
        assertThat(fallbacks("invalid")).isZero();
        assertThat(fallbacks("absent")).isZero();
    }

    @Test
    void theSameIpv6RealIpTwice_spelledTwoWays_isOneClient() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/auth/otp/send")
                .remoteAddress(NGINX_PEER)
                .header(ClientIpResolver.CLIENT_IP_HEADER, "2001:db8::1", "2001:0db8:0:0:0:0:0:1")
                .build();

        assertThat(keyResolver("preAuthIpKeyResolver").resolve(transformed(request)).block())
                .isEqualTo("ip:2001:db8:0:0:0:0:0:0/64");
        assertThat(fallbacks("invalid")).isZero();
    }

    @Test
    void aRealIpRepeatedWithAnInvalidValue_fallsBack() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/auth/otp/send")
                .remoteAddress(NGINX_PEER)
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7", "localhost")
                .build();

        assertThat(resolver.resolve(transformed(request))).isEqualTo(NGINX_PEER);
        assertThat(fallbacks("invalid")).isEqualTo(1.0);
    }

    @Test
    void noRealIp_withNoProxy_keysOnTheSocketPeer() {
        // Local dev, or a curl to NodePort 30080 from the box.
        String key = keyResolver("preAuthIpKeyResolver").resolve(edge(null, null)).block();

        assertThat(key).isEqualTo("ip:10.42.0.1");
        assertThat(fallbacks("absent")).isEqualTo(1.0);
        assertThat(fallbacks("invalid")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ipKeyedResolvers")
    void noRealIp_behindAnNginxNotYetUpdated_neverKeysOnXff(String name, String fallbackKey) {
        // The pre-fix behaviour, kept: one shared bucket, never a key the
        // caller picks by rotating its left-most XFF entry.
        KeyResolver keys = keyResolver(name);

        assertThat(keys.resolve(edge(null, "6.6.6.6, " + CLOUDFLARE_EDGE)).block()).isEqualTo(fallbackKey);
        assertThat(keys.resolve(edge(null, "7.7.7.7, " + CLOUDFLARE_EDGE)).block()).isEqualTo(fallbackKey);
    }

    // ------------------------------------------------------------ bearer vs IP-only

    @Test
    void aBearer_stillKeysTheDefaultResolver_butNeverTheIpOnlyOnes() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/otp/send")
                .remoteAddress(NGINX_PEER)
                .header(ClientIpResolver.CLIENT_IP_HEADER, "203.0.113.7")
                .header("Authorization", "Bearer rotating-garbage-1")
                .build();

        assertThat(keyResolver("gatewayKeyResolver").resolve(transformed(request)).block())
                .isEqualTo("token:rotating-garbage-1");
        assertThat(keyResolver("preAuthIpKeyResolver").resolve(transformed(request)).block())
                .isEqualTo("ip:203.0.113.7");
        assertThat(keyResolver("paymentsInnbucksIpKeyResolver").resolve(transformed(request)).block())
                .isEqualTo("ip:203.0.113.7");
    }

    // ------------------------------------------------------------ metric

    @Test
    void theFallbackCounters_existAtZero_andAValidHeaderMovesNeither() {
        assertThat(meters.find(ClientIpResolver.FALLBACK_METRIC).tag("reason", "absent").counter()).isNotNull();
        assertThat(meters.find(ClientIpResolver.FALLBACK_METRIC).tag("reason", "invalid").counter()).isNotNull();

        resolver.resolve(edge("203.0.113.7", "6.6.6.6, " + CLOUDFLARE_EDGE));

        assertThat(fallbacks("absent")).isZero();
        assertThat(fallbacks("invalid")).isZero();
    }

    @Test
    void parseLiteral_acceptsOnlyLiterals() {
        assertThat(ClientIpResolver.parseLiteral("203.0.113.7")).isNotNull();
        assertThat(ClientIpResolver.parseLiteral("2001:db8::1")).isNotNull();
        assertThat(ClientIpResolver.parseLiteral(null)).isNull();
        assertThat(ClientIpResolver.parseLiteral("localhost")).isNull();
        assertThat(ClientIpResolver.parseLiteral("example.com")).isNull();
    }

    // ------------------------------------------------------------ helpers

    /**
     * A request as it reaches the key resolvers: from nginx, carrying
     * {@code realIp} as X-Real-IP and {@code xff} as X-Forwarded-For (either
     * may be null), after the forward-headers transformer has run.
     */
    private static ServerWebExchange edge(String realIp, String xff) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/auth/otp/send")
                .remoteAddress(NGINX_PEER);
        if (realIp != null) {
            request.header(ClientIpResolver.CLIENT_IP_HEADER, realIp);
        }
        if (xff != null) {
            request.header("X-Forwarded-For", xff);
        }
        return transformed(request.build());
    }

    private static ServerWebExchange transformed(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request).mutate()
                .request(new ForwardedHeaderTransformer().apply(request))
                .build();
    }

    private KeyResolver keyResolver(String name) {
        return switch (name) {
            case "gatewayKeyResolver" -> config.gatewayKeyResolver(resolver);
            case "preAuthIpKeyResolver" -> config.preAuthIpKeyResolver(resolver);
            case "paymentsInnbucksIpKeyResolver" -> config.paymentsInnbucksIpKeyResolver(resolver);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private double fallbacks(String reason) {
        Counter counter = meters.find(ClientIpResolver.FALLBACK_METRIC).tag("reason", reason).counter();
        return counter == null ? -1 : counter.count();
    }
}
