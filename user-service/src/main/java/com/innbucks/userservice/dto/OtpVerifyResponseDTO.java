package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a successful OTP verification hands back.
 *
 * <p>Purely ADDITIVE: {@code POST /auth/otp/verify} previously answered with a
 * {@code null} data field, so a client built against the old shape keeps
 * working and simply ignores this.
 *
 * @param phoneNumber   the E.164 number the OTP proved possession of — the
 *                      normalized form the token is scoped to, echoed so a
 *                      client that sent a local-format number knows the
 *                      canonical spelling.
 * @param loyaltyToken  a bearer token for loyalty's AUTHENTICATED endpoints.
 *                      Scoped to loyalty ONLY: it carries no roles, so every
 *                      other service in the fleet rejects it. Send it as
 *                      {@code Authorization: Bearer <token>} on
 *                      {@code /loyalty/**} calls.
 * @param expiresInSeconds lifetime of {@code loyaltyToken}. There is no refresh
 *                      for it — when it expires the customer verifies a fresh
 *                      OTP.
 */
@Schema(description = "Result of a successful OTP verification, including the loyalty-scoped session token")
public record OtpVerifyResponseDTO(
        @Schema(description = "E.164 phone number the OTP proved ownership of", example = "+263771234567")
        String phoneNumber,

        @Schema(description = "Bearer token for /loyalty/** endpoints. Carries no roles, so it is rejected by every other service.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTY3In0.sig")
        String loyaltyToken,

        @Schema(description = "Lifetime of loyaltyToken in seconds", example = "43200")
        long expiresInSeconds
) {}
