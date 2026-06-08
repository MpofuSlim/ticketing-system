package innbucks.paymentservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Intercepts mutating requests that carry an {@code Idempotency-Key} header.
 * On a hit, the cached response is replayed byte-for-byte. On a miss the
 * request is executed and the resulting 2xx response is stored under the
 * key for {@link #TTL_SECONDS}.
 *
 * <p>Non-mutating methods pass straight through. For mutating requests the
 * behaviour splits two ways:
 * <ul>
 *   <li><b>Money-movement paths</b> ({@link #REQUIRED_PATHS}) — the
 *       Idempotency-Key header is <b>required</b>. A missing or blank
 *       value returns {@code 400} with {@code errorCode=idempotency_key_required}
 *       before any side effects run. This stops a double-tap on "Send" from
 *       executing a transfer twice.</li>
 *   <li><b>All other mutating paths</b> — the header is optional. A request
 *       without it bypasses the filter (matching the existing
 *       PaymentController / shop-checkout behaviour); a request with it
 *       gets the full replay protection.</li>
 * </ul>
 *
 * <p>On a cache hit the SHA-256 fingerprint of the inbound body is compared
 * to the fingerprint we stored alongside the cached response. A match
 * replays. A mismatch returns {@code 422} with
 * {@code errorCode=idempotency_conflict} — the standard Stripe-style
 * contract. Without this, a client reusing one key for "$1 transfer"
 * and then "$1000 transfer" would silently get the $1 response back for
 * the second call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final long TTL_SECONDS = 24 * 60 * 60; // 24h

    /**
     * Paths where the header is required (returns 400 on missing). The
     * money-movement endpoints — every other mutating endpoint stays
     * opt-in for backward compatibility.
     */
    static final Set<String> REQUIRED_PATHS = Set.of(
            "/payments/transfer",
            "/payments/withdraw",
            "/payments/innbucks"
    );

    private final IdempotencyStore store;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!MUTATING_METHODS.contains(request.getMethod().toUpperCase())) {
            return true;
        }
        // Required paths always go through the filter so a missing header
        // can be rejected with 400. Other paths only need the filter when
        // the header is present.
        String path = request.getRequestURI();
        if (REQUIRED_PATHS.contains(path)) {
            return false;
        }
        return isBlank(request.getHeader(HEADER));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(HEADER);
        String path = request.getRequestURI();

        if (isBlank(key)) {
            // Only reachable for REQUIRED_PATHS — shouldNotFilter would have
            // bypassed otherwise.
            writeJsonError(response, 400, "400 BAD_REQUEST",
                    "Idempotency-Key header is required for " + path,
                    "idempotency_key_required");
            log.warn("Idempotency-Key missing on required path={} method={}",
                    path, request.getMethod());
            return;
        }

        // Wrap once so the filter can read the body for SHA-256 AND the
        // controller's @RequestBody Jackson reader still sees the bytes.
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String bodyHash = sha256Hex(cachedRequest.getCachedBody());

        // Namespace by method + path so the same key used for different
        // endpoints doesn't collide (e.g. the client reusing one UUID for
        // both POST /payments/transfer and POST /payments/withdraw).
        String cacheKey = request.getMethod() + " " + path + "#" + key;

        var cached = store.get(cacheKey);
        if (cached.isPresent()) {
            StoredResponse prior = cached.get();
            if (!bodyHash.equals(prior.bodySha256())) {
                writeJsonError(response, 422, "422 UNPROCESSABLE_ENTITY",
                        "Idempotency-Key reused with a different request body — refusing to replay",
                        "idempotency_conflict");
                log.warn("Idempotency conflict key={} method={} path={} cachedHash={} incomingHash={}",
                        key, request.getMethod(), path, prior.bodySha256(), bodyHash);
                return;
            }
            log.info("Idempotency hit key={} method={} path={} replayStatus={}",
                    key, request.getMethod(), path, prior.status());
            response.setStatus(prior.status());
            if (prior.contentType() != null) {
                response.setContentType(prior.contentType());
            }
            response.getOutputStream().write(prior.body());
            response.getOutputStream().flush();
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, wrapper);
        } finally {
            if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
                StoredResponse snapshot = new StoredResponse(
                        wrapper.getStatus(),
                        wrapper.getContentType(),
                        wrapper.getContentAsByteArray(),
                        bodyHash
                );
                store.put(cacheKey, snapshot, TTL_SECONDS);
                log.debug("Idempotency cache store key={} status={} bodyHash={}",
                        key, wrapper.getStatus(), bodyHash);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JRE; if it's missing we have
            // bigger problems than idempotency.
            throw new IllegalStateException("SHA-256 not available in this JRE", e);
        }
    }

    /**
     * Emit a JSON {@code ApiResult}-shaped error directly to the response,
     * bypassing the controller advice. The filter runs before
     * {@code @RestControllerAdvice} can intervene, so we have to format
     * the envelope by hand to keep the contract consistent.
     */
    private static void writeJsonError(HttpServletResponse response, int status,
                                       String code, String message, String errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"code\":\"" + code + "\","
                + "\"message\":\"" + escape(message) + "\","
                + "\"data\":null,"
                + "\"errorCode\":\"" + errorCode + "\"}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }

    /** Minimal JSON-string escaping for the static error messages above. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
