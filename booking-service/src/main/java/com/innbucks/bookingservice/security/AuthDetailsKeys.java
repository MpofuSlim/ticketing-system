package com.innbucks.bookingservice.security;

/**
 * Keys for the per-request map stashed on
 * {@link org.springframework.security.core.Authentication#getDetails()} by
 * {@link JwtFilter}. Mirrors the keys used in user-service and event-
 * service so callers can read the JWT's cross-service UUID claims with
 * one shared vocabulary.
 */
public final class AuthDetailsKeys {
    private AuthDetailsKeys() {}

    public static final String USER_UUID = "userUuid";
    public static final String ORGANIZER_UUID = "organizerUuid";
}
