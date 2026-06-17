package com.innbucks.eventservice.security;

/**
 * Keys for the per-request map stashed on
 * {@link org.springframework.security.core.Authentication#getDetails()} by
 * {@link JwtFilter}. Mirrors user-service's keys (same JWT, same claim
 * names) so service-to-service flows that read these from the auth
 * context use one shared vocabulary.
 */
public final class AuthDetailsKeys {
    private AuthDetailsKeys() {}

    public static final String USER_UUID = "userUuid";
    public static final String ORGANIZER_UUID = "organizerUuid";
}
