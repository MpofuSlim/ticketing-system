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
 * The pool's TLS step: every host gets the system's TLS settings, except the
 * hosts listed here, which are offered TLS 1.2 only.
 *
 * <p><b>Why it exists.</b> Paynow's {@code www.paynow.co.zw} resets the
 * connection when a client offers TLS 1.3 instead of negotiating TLS 1.2
 * (measured from the ZW cell on 2026-11-26: {@code openssl s_client -tls1_2}
 * connects, {@code -tls1_3} gets {@code errno=104}). OpenSSL clients such as
 * curl fall back on their own; Java's does not, so every Paynow start from
 * payment-service failed with "Connection reset" before Paynow saw the request.
 * Every other partner keeps TLS 1.3. Remove a host once its server is fixed.
 *
 * <p><b>Why not {@code setTlsConfigResolver}.</b> HttpClient 5.6's classic
 * (blocking) path ignores a per-host {@code TlsConfig}'s protocols: the
 * strategy's own protocol list decides. So the host is matched here, on the
 * TLS name the connection operator passes in.
 */
public final class TlsProtocolsByHost implements TlsSocketStrategy {

    static final String TLS_1_2 = "TLSv1.2";

    private final Set<String> tls12OnlyHosts;
    private final TlsSocketStrategy normal;
    private final TlsSocketStrategy tls12Only;

    TlsProtocolsByHost(Collection<String> tls12OnlyHosts, TlsSocketStrategy normal, TlsSocketStrategy tls12Only) {
        this.tls12OnlyHosts = normalised(tls12OnlyHosts);
        this.normal = normal;
        this.tls12Only = tls12Only;
    }

    /** The pool's strategy: the system's SSL context, protocols, ciphers and hostname checks. */
    public static TlsProtocolsByHost systemDefault(Collection<String> tls12OnlyHosts) {
        return create(tls12OnlyHosts, SSLContexts.createSystemDefault(), HostnameVerificationPolicy.BUILTIN, null);
    }

    /**
     * Both strategies on one SSL context. The normal one is exactly
     * {@link DefaultClientTlsStrategy#createSystemDefault()} (system protocols
     * and ciphers); the other differs only in its protocol list. Tests pass
     * their own context and hostname checks.
     */
    static TlsProtocolsByHost create(Collection<String> tls12OnlyHosts, SSLContext context,
                                     HostnameVerificationPolicy policy, HostnameVerifier verifier) {
        String[] ciphers = HttpsSupport.getSystemCipherSuits();
        return new TlsProtocolsByHost(tls12OnlyHosts,
                new DefaultClientTlsStrategy(context, HttpsSupport.getSystemProtocols(), ciphers,
                        SSLBufferMode.STATIC, policy, verifier),
                new DefaultClientTlsStrategy(context, new String[] {TLS_1_2}, ciphers,
                        SSLBufferMode.STATIC, policy, verifier));
    }

    @Override
    public SSLSocket upgrade(Socket socket, String target, int port, Object attachment, HttpContext context)
            throws IOException {
        return strategyFor(target).upgrade(socket, target, port, attachment, context);
    }

    /** Whether {@code host} is offered TLS 1.2 only. */
    public boolean tls12Only(String host) {
        return host != null && tls12OnlyHosts.contains(normalise(host));
    }

    /** The hosts offered TLS 1.2 only, lower case. */
    public Set<String> tls12OnlyHosts() {
        return tls12OnlyHosts;
    }

    TlsSocketStrategy strategyFor(String host) {
        return tls12Only(host) ? tls12Only : normal;
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
