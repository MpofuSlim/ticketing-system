package innbucks.paymentservice.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.client.PaynowProperties;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.net.Socket;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paynow's hosts are offered TLS 1.2 only, every other host keeps the system's
 * TLS settings ({@link TlsProtocolsByHost}). Paynow resets a TLS 1.3 offer
 * (measured 2026-11-26), and HttpClient's classic path ignores a per-host
 * {@code TlsConfig}, so the protocol is decided by the strategy picked for the
 * host. The handshake cases run a real handshake against a local TLS server:
 * a strategy that only claims to pin TLS 1.2 fails them.
 */
class TlsProtocolsByHostTest {

    private static WireMockServer tlsServer;
    private static SSLContext trustAll;

    @BeforeAll
    static void start() throws Exception {
        tlsServer = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort());
        tlsServer.start();
        // The local server's certificate is WireMock's own: trust it, for the test only.
        trustAll = SSLContexts.custom().loadTrustMaterial(TrustAllStrategy.INSTANCE).build();
    }

    @AfterAll
    static void stop() {
        tlsServer.stop();
    }

    @Test
    void aListedHost_negotiatesTls12_evenThoughTheServerOffersTls13() throws Exception {
        TlsProtocolsByHost tls = TlsProtocolsByHost.create(List.of("localhost"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        assertThat(handshake(tls)).isEqualTo("TLSv1.2");
    }

    @Test
    void anyOtherHost_keepsTheSystemDefault_TLS13WhereTheServerHasIt() throws Exception {
        TlsProtocolsByHost tls = TlsProtocolsByHost.create(List.of("www.paynow.co.zw"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        assertThat(handshake(tls)).isEqualTo("TLSv1.3");
    }

    @Test
    void hostsMatchWhateverTheirCaseOrTrailingDot() {
        TlsProtocolsByHost tls = TlsProtocolsByHost.systemDefault(List.of(" WWW.Paynow.co.zw ", "", "  "));

        assertThat(tls.tls12OnlyHosts()).containsExactly("www.paynow.co.zw");
        assertThat(tls.tls12Only("www.paynow.co.zw")).isTrue();
        assertThat(tls.tls12Only("WWW.PAYNOW.CO.ZW.")).isTrue();
        assertThat(tls.tls12Only("paynow.co.zw")).isFalse();
        assertThat(tls.tls12Only("payonline.ecocash.co.zw")).isFalse();
        assertThat(tls.tls12Only(null)).isFalse();
    }

    @Test
    void thePool_offersPaynowsHostsTls12Only_andNoOneElse() {
        PaynowProperties paynow = new PaynowProperties();
        paynow.setAllowedHosts("www.paynow.co.zw, Pay.Paynow.co.zw");

        assertThat(paynow.hosts()).containsExactly("pay.paynow.co.zw", "www.paynow.co.zw");
        try (PooledHttpClient pool = new PooledHttpClient(new PooledHttpClientProperties(), paynow.hosts())) {
            assertThat(pool.tls().tls12Only("www.paynow.co.zw")).isTrue();
            assertThat(pool.tls().tls12Only("pay.paynow.co.zw")).isTrue();
            assertThat(pool.tls().tls12Only("staging.innbucks.co.zw")).isFalse();
            assertThat(pool.tls().tls12Only("payonline.econet.co.zw")).isFalse();
        }
        try (PooledHttpClient pool = new PooledHttpClient(new PooledHttpClientProperties())) {
            assertThat(pool.tls().tls12OnlyHosts()).isEmpty();
        }
    }

    @Test
    void paynowHosts_includeTheBaseUrlsHost_whenNoAllowedHostsAreSet() {
        PaynowProperties paynow = new PaynowProperties();

        assertThat(paynow.hosts()).containsExactly("www.paynow.co.zw");

        paynow.setBaseUrl("not a url");
        assertThat(paynow.hosts()).isEqualTo(Set.of());
    }

    private static String handshake(TlsProtocolsByHost tls) throws Exception {
        int port = tlsServer.httpsPort();
        Socket plain = new Socket("localhost", port);
        try (SSLSocket socket = tls.upgrade(plain, "localhost", port, null, HttpClientContext.create())) {
            return socket.getSession().getProtocol();
        }
    }
}
