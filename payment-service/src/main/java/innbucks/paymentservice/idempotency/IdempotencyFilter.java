package innbucks.paymentservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Intercepts mutating requests that carry an {@code Idempotency-Key} header.
 * On a hit, the cached response is replayed byte-for-byte. On a miss the
 * request is executed and the resulting 2xx response is stored under the
 * key for {@link #TTL_SECONDS}.
 *
 * <p>Non-mutating methods and requests without the header pass straight
 * through. This is the standard Stripe-style idempotency contract — clients
 * generate a UUID per logical operation and send it on every retry; the
 * server returns the original response without re-executing side effects.
 *
 * <p>Why payment-service needs this: POST /payments and POST /payments/
 * shop-checkout are money paths. A network blip causing the client to retry
 * without idempotency would, on the cash leg of a shop-checkout, double the
 * customer's points earned and double-debit the points portion. The
 * Idempotency-Key header turns the retry into a no-op replay.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final long TTL_SECONDS = 24 * 60 * 60; // 24h

    private final IdempotencyStore store;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING_METHODS.contains(request.getMethod().toUpperCase())
                || isBlank(request.getHeader(HEADER));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(HEADER);

        // Namespace by method + path so the same key used for different
        // endpoints doesn't collide (e.g. the client reusing one UUID for
        // both POST /payments and POST /payments/shop-checkout).
        String cacheKey = request.getMethod() + " " + request.getRequestURI() + "#" + key;

        var cached = store.get(cacheKey);
        if (cached.isPresent()) {
            StoredResponse prior = cached.get();
            log.info("Idempotency hit key={} method={} path={} replayStatus={}",
                    key, request.getMethod(), request.getRequestURI(), prior.status());
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
            chain.doFilter(request, wrapper);
        } finally {
            if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
                StoredResponse snapshot = new StoredResponse(
                        wrapper.getStatus(),
                        wrapper.getContentType(),
                        wrapper.getContentAsByteArray()
                );
                store.put(cacheKey, snapshot, TTL_SECONDS);
                log.debug("Idempotency cache store key={} status={}", key, wrapper.getStatus());
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
