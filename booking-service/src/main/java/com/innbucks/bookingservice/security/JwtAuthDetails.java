package com.innbucks.bookingservice.security;

import java.util.UUID;

/**
 * Shape of {@code Authentication.getDetails()} for JWT-authenticated requests
 * — read by controllers + interceptors to grab JWT claims without re-parsing
 * the token.
 *
 * <p>{@code userUuid} / {@code organizerUuid} are nullable: tokens minted
 * before user-service's V20 don't carry the claims. Consumers branch on
 * null to fall back to the legacy email-based code path.
 *
 * <p>{@code firstName} / {@code lastName} are nullable too: emitted only for
 * CUSTOMER, TEAM_MEMBER and EVENT_ORGANIZER tokens (see user-service JwtUtil).
 * The scan boundary uses them to render a human display name on the rejection
 * toast; consumers fall back to {@code email} when absent.
 */
public record JwtAuthDetails(
        String email,
        String phoneNumber,
        UUID userUuid,
        UUID organizerUuid,
        String firstName,
        String lastName
) { }
