package com.innbucks.eventservice.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What a gate-only session may reach here.
 *
 * <p>user-service mints that session for the Organizer app's phone sign-in
 * ({@code POST /auth/gate/sign-in}): an SMS code proves the phone, nothing else
 * about the person, so the token carries only the {@code GATE_SCANNER} role. No
 * account can hold that role (user-service reserves the name), so a token with
 * it is always one of these sessions.
 *
 * <p>Here it reaches the gate screen and its invites, and the public event
 * reads it would get with no token at all. {@code GET /events/sales} (a sales
 * agent's events) and the organizer surface are refused, and so is every write
 * but accepting or declining an invite. Checked in {@link JwtFilter}, so a new
 * endpoint is closed to it until it is added here.
 */
public final class GateSessionScope {

    /** The one role a gate-only session carries. */
    public static final String ROLE = "GATE_SCANNER";

    private static final Pattern INVITE_DECISION =
            Pattern.compile("^/events/gate/invites/[^/]+/(accept|decline)$");

    private GateSessionScope() {
    }

    public static boolean isGateSession(List<String> roles) {
        return roles != null && roles.contains(ROLE);
    }

    public static boolean allows(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        if ("OPTIONS".equals(method)) {
            return true;
        }
        if ("GET".equals(method)) {
            // The gate screen, and the public event reads (answered as for an
            // anonymous caller: the session holds no organizer or staff role).
            return !path.startsWith("/event-organizer") && !path.equals("/events/sales");
        }
        return "POST".equals(method) && INVITE_DECISION.matcher(path).matches();
    }
}
