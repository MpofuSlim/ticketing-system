package com.innbucks.userservice.security;

import java.time.Instant;

/**
 * The validated contents of a customer KYC-upgrade verification token
 * (see {@link KycVerificationTokenService}).
 *
 * <p>{@code phoneNumber} is the <strong>authoritative</strong> identity for a
 * tier-2/3/4 upgrade — it comes from the signed token, never from the request
 * body/param — so a caller cannot name a victim by phone. {@code jti} keys the
 * single-use {@link ConsumedKycTokenStore} replay guard.
 */
public record VerifiedKycToken(String phoneNumber, String jti, Instant expiresAt) {
}
