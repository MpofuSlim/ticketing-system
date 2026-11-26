package innbucks.paymentservice.config;

import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.apache.hc.core5.ssl.SSLContexts;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pool's TLS step: every host gets HttpClient's default TLS settings,
 * except the hosts listed here, which are offered the JVM's own cipher list.
 *
 * <p><b>Why it exists.</b> With no cipher list configured, HttpClient filters
 * the JVM's list through {@code TlsCiphers.excludeWeak}, which also removes
 * every cipher on the HTTP/2 blocklist, and that list holds every CBC cipher.
 * Paynow's {@code www.paynow.co.zw} accepts only CBC ciphers
 * ({@code ECDHE-RSA-AES*-CBC-SHA256/384}), and when it shares no cipher with
 * the client it resets the connection instead of sending a TLS alert. So every
 * Paynow call from payment-service failed with "Connection reset" before Paynow
 * saw the request, while curl, wget and plain Java TLS (which all offer CBC)
 * reached it. Measured from the payment-service pod on 2026-11-26.
 *
 * <p>The JVM's list is plain Java's: its own defaults, already without the
 * broken ciphers {@code jdk.tls.disabledAlgorithms} removes. Every other host
 * keeps HttpClient's stricter list. Remove a host once its server offers a GCM
 * cipher.
 *
 * <p><b>TLS version is not the problem.</b> Paynow has no TLS 1.3, so
 * {@code openssl -tls1_3} is reset, but Java offers TLS 1.3 and 1.2 together
 * and Paynow picks 1.2. Protocols stay the system's for every host.
 */
public final class TlsCiphersByHost implements TlsSocketStrategy {

    private final Set<String> jvmCipherHosts;
    private final TlsSocketStrategy normal;
    private final TlsSocketStrategy jvmCiphers;

    TlsCiphersByHost(Collection<String> jvmCipherHosts, TlsSocketStrategy normal, TlsSocketStrategy jvmCiphers) {
        this.jvmCipherHosts = normalised(jvmCipherHosts);
        this.normal = normal;
        this.jvmCiphers = jvmCiphers;
    }

    /** The pool's strategy: the system's SSL context, protocols and hostname checks. */
    public static TlsCiphersByHost systemDefault(Collection<String> jvmCipherHosts) {
        return create(jvmCipherHosts, SSLContexts.createSystemDefault(), HostnameVerificationPolicy.BUILTIN, null);
    }

    /**
     * Both strategies on one SSL context. The normal one is exactly
     * {@link DefaultClientTlsStrategy#createSystemDefault()}. The other differs
     * only in its cipher list: the context's own defaults, unless
     * {@code https.cipherSuites} names a list, which then applies to both.
     * Tests pass their own context and hostname checks.
     */
    static TlsCiphersByHost create(Collection<String> jvmCipherHosts, SSLContext context,
                                   HostnameVerificationPolicy policy, HostnameVerifier verifier) {
        String[] protocols = HttpsSupport.getSystemProtocols();
        String[] systemCiphers = HttpsSupport.getSystemCipherSuits();
        String[] jvmDefault = systemCiphers != null ? systemCiphers
                : context.getDefaultSSLParameters().getCipherSuites();
        return new TlsCiphersByHost(jvmCipherHosts,
                new DefaultClientTlsStrategy(context, protocols, systemCiphers,
                        SSLBufferMode.STATIC, policy, verifier),
                new DefaultClientTlsStrategy(context, protocols, jvmDefault,
                        SSLBufferMode.STATIC, policy, verifier));
    }

    @Override
    public SSLSocket upgrade(Socket socket, String target, int port, Object attachment, HttpContext context)
            throws IOException {
        return strategyFor(target).upgrade(socket, target, port, attachment, context);
    }

    /** Whether {@code host} is offered the JVM's own cipher list. */
    public boolean jvmCiphers(String host) {
        return host != null && jvmCipherHosts.contains(normalise(host));
    }

    /** The hosts offered the JVM's own cipher list, lower case. */
    public Set<String> jvmCipherHosts() {
        return jvmCipherHosts;
    }

    TlsSocketStrategy strategyFor(String host) {
        return jvmCiphers(host) ? jvmCiphers : normal;
    }

    private static Set<String> normalised(Collection<String> hosts) {
        Set<String> out = new TreeSet<>();
        if (hosts != null) {
            for (String h : hosts) {
                if (h != null && !h.isBlank()) {
                    out.add(normalise(h));
                }
            }
        }
        return Set.copyOf(out);
    }

    private static String normalise(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
}
