package com.innbucks.bookingservice.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/**
 * What a gate-only session may reach here.
 *
 * <p>user-service mints that session for the Organizer app's phone sign-in
 * ({@code POST /auth/gate/sign-in}): an SMS code proves the phone, nothing else
 * about the person, so the token carries only the {@code GATE_SCANNER} role. No
 * account can hold that role (user-service reserves the name), so a token with
 * it is always one of these sessions.
 *
 * <p>Here it reaches the four gate endpoints and nothing else: not My tickets,
 * not a booking, not the box office. Its authority to scan is event-service's
 * live team answer, exactly as for a Super app scanner. Checked in
 * {@link JwtFilter}, so a new endpoint is closed to it until it is added here.
 */
public final class GateSessionScope {

    /** The one role a gate-only session carries. */
    public static final String ROLE = "GATE_SCANNER";

    private static final Set<String> POSTS = Set.of("/tickets/scan", "/tickets/lookup");
    private static final Set<String> GETS = Set.of("/scans/me", "/scans/me/stats");

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
        if ("POST".equals(method)) {
            return POSTS.contains(path);
        }
        return "GET".equals(method) && GETS.contains(path);
    }
}
