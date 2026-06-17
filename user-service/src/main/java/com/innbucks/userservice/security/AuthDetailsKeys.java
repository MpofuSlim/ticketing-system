package com.innbucks.userservice.security;

/**
 * Keys for the per-request map stashed on
 * {@link org.springframework.security.core.Authentication#getDetails()} by
 * {@link JwtFilter}. Centralised so the producer and every consumer agree
 * on the string literal — a typo would silently miss the claim and
 * surface as a null in the controller.
 */
public final class AuthDetailsKeys {
    private AuthDetailsKeys() {}

    public static final String USER_UUID = "userUuid";
    public static final String ORGANIZER_UUID = "organizerUuid";
}
