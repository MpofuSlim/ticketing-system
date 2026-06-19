package com.innbucks.seatservice.security;

import java.util.UUID;

/**
 * Shape of {@code Authentication.getDetails()} for JWT-authenticated requests.
 * {@code email} / {@code phoneNumber} feed the tier interceptor.
 * {@code organizerUuid} is the stable owning-organizer pointer — null on
 * customer tokens, populated on EVENT_ORGANIZER and TEAM_MEMBER tokens — and
 * is what the seat-category ownership check matches against the event's
 * {@code tenantUserUuid}.
 */
public record JwtAuthDetails(String email, String phoneNumber, UUID organizerUuid) { }
