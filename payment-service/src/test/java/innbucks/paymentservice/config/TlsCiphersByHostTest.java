package innbucks.paymentservice.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import innbucks.paymentservice.client.PaynowProperties;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.ssl.SSLContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paynow's hosts are offered the JVM's own cipher list, every other host keeps
 * HttpClient's default ({@link TlsCiphersByHost}). Paynow accepts only CBC
 * ciphers, and HttpClient's default list drops every CBC cipher (measured from
 * the payment-service pod on 2026-11-26). The server below is shaped like
 * Paynow: TLS 1.2, CBC ciphers only. Every case runs a real handshake, and the
 * request cases go through HttpClient itself, the path payment-service uses.
 */
class TlsCiphersByHostTest {

    /** The two ciphers Paynow was seen to pick, from openssl and from plain Java. */
    private static final String[] PAYNOW_LIKE_CIPHERS = {
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
    };

    private static SSLServerSocket cbcOnlyServer;
    private static WireMockServer normalServer;
    private static SSLContext trustAll;

    @BeforeAll
    static void start() throws Exception {
        trustAll = SSLContexts.custom().loadTrustMaterial(TrustAllStrategy.INSTANCE).build();
        cbcOnlyServer = cbcOnlyServer();
        normalServer = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort());
        normalServer.start();
        normalServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("ok")));
    }

    @AfterAll
    static void stop() throws Exception {
        cbcOnlyServer.close();
        normalServer.stop();
    }

    @Test
    void aListedHost_reachesACbcOnlyServer_throughHttpClient() throws Exception {
        TlsCiphersByHost tls = TlsCiphersByHost.create(List.of("localhost"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        assertThat(request(tls, cbcOnlyServer.getLocalPort())).isEqualTo("200 ok");
        assertThat(handshake(tls, cbcOnlyServer.getLocalPort()))
                .startsWith("TLSv1.2 TLS_ECDHE_RSA_WITH_AES_")
                .contains("_CBC_");
    }

    @Test
    void httpClientsDefault_cannotReachACbcOnlyServer_whichIsWhyTheListExists() {
        TlsCiphersByHost tls = TlsCiphersByHost.create(List.of("www.paynow.co.zw"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        // HttpClient's default offers no CBC cipher, so there is nothing to agree
        // on. Paynow answers that with a reset; the JDK server sends an alert.
        assertThatThrownBy(() -> request(tls, cbcOnlyServer.getLocalPort()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void everyOtherHost_keepsHttpClientsDefault_andTls13() throws Exception {
        TlsCiphersByHost tls = TlsCiphersByHost.create(List.of("www.paynow.co.zw"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        assertThat(request(tls, normalServer.httpsPort())).isEqualTo("200 ok");
        assertThat(handshake(tls, normalServer.httpsPort())).startsWith("TLSv1.3 ");
    }

    @Test
    void aListedHost_isNotPinnedToTls12_againstAServerWithTls13() throws Exception {
        TlsCiphersByHost tls = TlsCiphersByHost.create(List.of("localhost"), trustAll,
                HostnameVerificationPolicy.CLIENT, NoopHostnameVerifier.INSTANCE);

        assertThat(handshake(tls, normalServer.httpsPort())).startsWith("TLSv1.3 ");
    }

    @Test
    void hostsMatchWhateverTheirCaseOrTrailingDot() {
        TlsCiphersByHost tls = TlsCiphersByHost.systemDefault(List.of(" WWW.Paynow.co.zw ", "", "  "));

        assertThat(tls.jvmCipherHosts()).containsExactly("www.paynow.co.zw");
        assertThat(tls.jvmCiphers("www.paynow.co.zw")).isTrue();
        assertThat(tls.jvmCiphers("WWW.PAYNOW.CO.ZW.")).isTrue();
        assertThat(tls.jvmCiphers("paynow.co.zw")).isFalse();
        assertThat(tls.jvmCiphers("payonline.ecocash.co.zw")).isFalse();
        assertThat(tls.jvmCiphers(null)).isFalse();
    }

    @Test
    void thePool_offersPaynowsHostsTheJvmCiphers_andNoOneElse() {
        PaynowProperties paynow = new PaynowProperties();
        paynow.setAllowedHosts("www.paynow.co.zw, Pay.Paynow.co.zw");

        assertThat(paynow.hosts()).containsExactly("pay.paynow.co.zw", "www.paynow.co.zw");
        try (PooledHttpClient pool = new PooledHttpClient(new PooledHttpClientProperties(), paynow.hosts())) {
            assertThat(pool.tls().jvmCiphers("www.paynow.co.zw")).isTrue();
            assertThat(pool.tls().jvmCiphers("pay.paynow.co.zw")).isTrue();
            assertThat(pool.tls().jvmCiphers("staging.innbucks.co.zw")).isFalse();
            assertThat(pool.tls().jvmCiphers("payonline.econet.co.zw")).isFalse();
        }
        try (PooledHttpClient pool = new PooledHttpClient(new PooledHttpClientProperties())) {
            assertThat(pool.tls().jvmCipherHosts()).isEmpty();
        }
    }

    @Test
    void paynowHosts_includeTheBaseUrlsHost_whenNoAllowedHostsAreSet() {
        PaynowProperties paynow = new PaynowProperties();

        assertThat(paynow.hosts()).containsExactly("www.paynow.co.zw");

        paynow.setBaseUrl("not a url");
        assertThat(paynow.hosts()).isEqualTo(Set.of());
    }

    /** A GET through HttpClient over {@code tls}, as {@code "<status> <body>"}. */
    private static String request(TlsCiphersByHost tls, int port) throws Exception {
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setTlsSocketStrategy(tls).build())
                .disableAutomaticRetries()
                .build()) {
            return client.execute(new HttpGet("https://localhost:" + port + "/"),
                    res -> res.getCode() + " " + EntityUtils.toString(res.getEntity()));
        }
    }

    private static String handshake(TlsCiphersByHost tls, int port) throws Exception {
        Socket plain = new Socket("localhost", port);
        try (SSLSocket socket = tls.upgrade(plain, "localhost", port, null, HttpClientContext.create())) {
            return socket.getSession().getProtocol() + " " + socket.getSession().getCipherSuite();
        }
    }

    /**
     * A TLS 1.2 server with CBC ciphers only, like Paynow's, on WireMock's own
     * test certificate (RSA, so the ECDHE_RSA ciphers apply). It answers any
     * request with {@code 200 ok}.
     */
    private static SSLServerSocket cbcOnlyServer() throws Exception {
        KeyStore keys = KeyStore.getInstance("JKS");
        try (InputStream in = WireMockServer.class.getResourceAsStream("/keystore")) {
            keys.load(in, "password".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "password".toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket socket = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        socket.setEnabledProtocols(new String[] {"TLSv1.2"});
        socket.setEnabledCipherSuites(PAYNOW_LIKE_CIPHERS);
        Thread accept = new Thread(() -> serve(socket), "cbc-only-tls-server");
        accept.setDaemon(true);
        accept.start();
        return socket;
    }

    private static void serve(SSLServerSocket server) {
        while (!server.isClosed()) {
            try (SSLSocket s = (SSLSocket) server.accept()) {
                s.setSoTimeout(5000);
                s.startHandshake();
                InputStream in = s.getInputStream();
                StringBuilder head = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    head.append((char) b);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                OutputStream out = s.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception e) {
                // a refused handshake or a probe that only shook hands; serve the next one
            }
        }
    }
}
