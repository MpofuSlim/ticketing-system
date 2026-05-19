package innbucks.paymentservice.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the request body once at construction, caches it in memory, and
 * serves subsequent {@link #getInputStream()} calls from that cache.
 *
 * <p>The idempotency filter needs to read the body to compute a SHA-256
 * fingerprint <i>before</i> the controller runs (otherwise a same-key /
 * different-body replay can't be detected and silently runs the cached
 * 200 response back). But once a servlet's {@code InputStream} is
 * consumed it can't be re-read, so the controller's {@code @RequestBody}
 * Jackson deserialiser would see an empty body. This wrapper bridges
 * that: it slurps the body up front, and every subsequent reader sees
 * a fresh {@link ServletInputStream} over the cached byte array.
 *
 * <p>Memory cost is bounded by the servlet container's max-payload
 * setting — for our deposit/withdraw bodies (a few hundred bytes) it's
 * negligible.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
    }

    /** Raw body bytes captured at construction. Safe to read repeatedly. */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException(
                        "CachedBodyHttpServletRequest is sync-only; async I/O isn't supported");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
