package com.innbucks.seatservice.security;

/**
 * Shape of {@code Authentication.getDetails()} for JWT-authenticated requests
 * — used by the tier interceptor to look up the customer's live tier from
 * user-service.
 */
public record JwtAuthDetails(String email, String phoneNumber) { }
