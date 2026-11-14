package com.innbucks.userservice.security;

import java.util.List;
import java.util.Locale;

/**
 * The session the Organizer app's gate sign-in mints ({@code POST /auth/gate/sign-in},
 * V57): a phone number proved by an SMS code, and nothing else about the person.
 *
 * <p>Its token carries only the {@link #ROLE} role. booking-service and
 * event-service let it reach their gate endpoints and nothing else (their own
 * {@code GateSessionScope}); here it reaches nothing at all. {@link JwtFilter}
 * refuses it on every path it reads, which matters most for
 * {@code /auth/devices}: an SMS code must never be enough to list or remove the
 * phones signed in to that number's InnBucks app. {@code /auth/refresh} and
 * {@code /auth/logout} are not filtered and keep working, so the app can renew
 * and end its session.
 *
 * <p>The role name is reserved ({@link #isReservedName}): no account can hold
 * it, so a token with it is always one of these sessions, and the confinement
 * in the other services can key on it alone.
 */
public final class GateSession {

    /** The one role a gate session carries. */
    public static final String ROLE = "GATE_SCANNER";

    private GateSession() {
    }

    public static boolean isGateSession(List<String> roles) {
        return roles != null && roles.contains(ROLE);
    }

    /** True for any spelling of the gate role, which no account may hold. */
    public static boolean isReservedName(String roleName) {
        return roleName != null && ROLE.equals(roleName.trim().toUpperCase(Locale.ROOT));
    }
}
