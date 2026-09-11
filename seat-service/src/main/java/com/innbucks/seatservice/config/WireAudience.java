package com.innbucks.seatservice.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is going to read the JSON we are about to write — a person, or another
 * service?
 *
 * <p>CLAUDE.md's wire-format rule splits on exactly this: a human-facing
 * response carries the MARKET OFFSET so the client can print it verbatim, while
 * an S2S payload stays {@code Z} because a per-cell offset there invites
 * double-conversion. A single DTO class is often served on both surfaces, so
 * the audience cannot be a property of the type. It is a property of the
 * request, which is what this reads.
 *
 * <p><b>Two markers, because neither alone is enough.</b> The {@code /internal/}
 * path segment covers the endpoints built for S2S. It does not cover a sibling
 * service calling a public path — booking-service fetches
 * {@code GET /events/{id}}, not the internal variant, and that response carries
 * {@code startDateTime}. So callers also stamp {@link #HEADER} on every
 * outbound S2S request; that is the marker that closes the hole.
 *
 * <p><b>Absent a request, we answer S2S.</b> Serialization outside an HTTP
 * request — a domain event, a scheduled job, a log line — has no market reader
 * and must stay UTC. Defaulting the other way would quietly shift stored and
 * published timestamps, which is the bug class the UTC rule exists to prevent.
 * The header is not a trust boundary: a client that sets it only makes its own
 * timestamps harder to read.
 */
public final class WireAudience {

    /** Stamped by callers on every outbound service-to-service request. */
    public static final String HEADER = "X-Innbucks-S2S";

    private static final String INTERNAL_SEGMENT = "/internal";

    private WireAudience() {
    }

    /** True when this response is for another service rather than a person. */
    public static boolean isServiceToService() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return true;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        if (request.getHeader(HEADER) != null) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && (uri.contains(INTERNAL_SEGMENT + "/") || uri.endsWith(INTERNAL_SEGMENT));
    }
}
