package com.innbucks.bookingservice.security;

/**
 * Shape of {@code Authentication.getDetails()} for JWT-authenticated requests
 * — read by controllers to grab the JWT's {@code phoneNumber} claim without
 * re-parsing the token.
 */
public record JwtAuthDetails(String email, String phoneNumber) { }
