package com.innbucks.bookingservice.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Intercepts mutating requests that carry an {@code Idempotency-Key} header.
 *
 * <p>On a hit, the cached response is replayed byte-for-byte. On a miss the
 * slot is atomically reserved before the request runs; concurrent callers
 * presenting the same key see the reservation and get a 409 instead of
 * both executing. On 2xx the reservation is overwritten by a completed
 * entry that survives for {@link #COMPLETED_TTL_SECONDS}; on non-2xx or
 * exception the reservation is released so a client retry can proceed.
 *
 * <p>The cache key is namespaced by caller identity (JWT email if
 * authenticated, else client IP) so user B cannot replay user A's cached
 * response by guessing or stealing the Idempotency-Key.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    // 24 hours — same as payment-service's filter. The previous 10-min TTL was
    // shorter than realistic payment-processor retry windows: a payment-service
    // call that timed out and retried at minute 11 would find the cache expired,
    // hit the now-CONFIRMED booking, and 409 even though the booking was fine —
    // surfacing to the customer as a false "your booking failed" right after a
    // successful confirm. confirmBooking() is now ALSO idempotent for
    // already-CONFIRMED bookings (returns the existing DTO), so this TTL bump
    // is belt-and-braces: both layers must lapse before a legitimately-retried
    // request could see anything other than the original response.
    static final long COMPLETED_TTL_SECONDS = 24 * 60 * 60;
    static final long RESERVATION_TTL_SECONDS = 30;
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

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
        String scope = resolveScope(request);
        String cacheKey = request.getMethod() + " " + request.getRequestURI() + "#" + scope + "#" + key;

        Optional<IdempotencyEntry> existing = store.get(cacheKey);
        if (existing.isPresent()) {
            IdempotencyEntry entry = existing.get();
            if (entry instanceof IdempotencyEntry.Completed completed) {
                log.info("Idempotency hit key={} scope={} method={} path={} replayStatus={}",
                        key, scope, request.getMethod(), request.getRequestURI(),
                        completed.response().status());
                replay(completed.response(), response);
                return;
            }
            // Reserved: another request with this key is in flight.
            log.info("Idempotency in-progress key={} scope={} method={} path={}",
                    key, scope, request.getMethod(), request.getRequestURI());
            writeConflict(response);
            return;
        }

        if (!store.tryReserve(cacheKey, RESERVATION_TTL_SECONDS)) {
            // Lost a race between get() and tryReserve(): another caller
            // claimed the slot in between.
            log.info("Idempotency reservation lost key={} scope={} method={} path={}",
                    key, scope, request.getMethod(), request.getRequestURI());
            writeConflict(response);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, wrapper);
            if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
                StoredResponse snapshot = new StoredResponse(
                        wrapper.getStatus(),
                        wrapper.getContentType(),
                        wrapper.getContentAsByteArray()
                );
                store.put(cacheKey, snapshot, COMPLETED_TTL_SECONDS);
                stored = true;
                log.debug("Idempotency cache store key={} scope={} status={}",
                        key, scope, wrapper.getStatus());
            }
        } finally {
            if (!stored) {
                try {
                    store.release(cacheKey);
                } catch (Exception e) {
                    log.warn("Failed to release idempotency reservation cacheKey={}", cacheKey, e);
                }
            }
            wrapper.copyBodyToResponse();
        }
    }

    private void replay(StoredResponse prior, HttpServletResponse response) throws IOException {
        response.setStatus(prior.status());
        if (prior.contentType() != null) {
            response.setContentType(prior.contentType());
        }
        response.getOutputStream().write(prior.body());
        response.getOutputStream().flush();
    }

    private void writeConflict(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.setHeader("Retry-After", "5");
        response.getWriter().write(
                "{\"code\":\"REQUEST_IN_PROGRESS\","
                        + "\"message\":\"Another request with this Idempotency-Key is in progress; retry shortly\","
                        + "\"data\":null}"
        );
    }

    /**
     * Namespace the cache by caller identity so user B can't replay user A's
     * cached response with a known/guessed Idempotency-Key. Authenticated
     * callers scope by JWT subject; guests scope by IP (X-Forwarded-For
     * left-most token if present, else the immediate peer).
     */
    private String resolveScope(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && auth.getPrincipal() != null
                && !ANONYMOUS_PRINCIPAL.equals(auth.getPrincipal())) {
            String name = auth.getName();
            if (name != null && !name.isBlank()) {
                return "user:" + name;
            }
        }
        return "ip:" + extractClientIp(request);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma >= 0 ? xff.substring(0, comma) : xff).trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
