package com.innbucks.userservice.dto;

import lombok.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "AuthResponse",
        description = "Authentication result. On a successful login, `token` is populated and the client should send it " +
                "as `Authorization: Bearer <token>` on subsequent requests. If MFA is required but no OTP was supplied, " +
                "`mfaRequired=true` is returned with no token — the client should prompt for the OTP and re-submit."
)
public class AuthResponseDTO {

    @Schema(description = "JWT access token. Null when `mfaRequired=true` or on registration responses.",
            nullable = true)
    private String token;

    @Schema(description = "Long-lived JWT refresh token. Returned on a successful login (and on /auth/refresh). " +
            "Use it ONLY at `POST /auth/refresh` to obtain a fresh access token — it is not accepted on " +
            "any other endpoint. Null on registration responses and when `mfaRequired=true`.",
            nullable = true)
    private String refreshToken;

    @Schema(description = "Principal's roles. A user may hold any combination of SUPER_ADMIN, EVENT_ORGANIZER, MERCHANT_ADMIN. CUSTOMER accounts always have exactly one role: CUSTOMER.",
            example = "[\"EVENT_ORGANIZER\"]")
    private List<String> roles;

    @Schema(description = "Default services this user is enrolled in (ticketing, loyalty).",
            example = "[\"ticketing\"]",
            nullable = true)
    private List<String> defaultServices;

    @Schema(description = "Email address if the account has one. Null / omitted for customers who registered " +
            "with a phone number only.",
            nullable = true)
    private String email;

    @Schema(description = "True when MFA is required to complete this login. `token`/`refreshToken` are null; "
            + "the FE prompts for the 6-digit TOTP (or a backup code) and POSTs it to /auth/login/mfa together "
            + "with the `mfaToken` field.",
            example = "false")
    private boolean mfaRequired;

    @Schema(description = "True when the user MUST enrol in MFA before proceeding (system user on web/mobile "
            + "with no TOTP secret yet). The FE should call POST /auth/mfa/enroll/start with `mfaToken` to "
            + "receive a QR / otpauth URI to scan, then POST /auth/mfa/enroll/complete with the first code. "
            + "Omitted from the wire when null.",
            nullable = true, example = "true")
    private Boolean mfaEnrollmentRequired;

    @Schema(description = "Short-lived (~5 min) signed token issued alongside mfaRequired / "
            + "mfaEnrollmentRequired. The FE echoes it back on /auth/login/mfa or /auth/mfa/enroll/* to "
            + "prove step-1 of login was completed. Null when no MFA challenge is in flight.",
            nullable = true)
    private String mfaToken;

    @Schema(description = "True when the user must change their password before continuing. Set after a " +
            "SUPER_ADMIN approves the account and a one-time temporary password is assigned (delivered to " +
            "the user over email/SMS); the client should route the user to a change-password screen. " +
            "Cleared once the password is changed.",
            example = "false")
    private boolean mustChangePassword;

    @Schema(description = "Customer registration tier (1..4). Present only for CUSTOMER accounts; null for system users.",
            example = "2",
            nullable = true)
    private Integer tier;

    @Schema(description = "True when the customer has completed tier 4 verification, or for system users. " +
            "Reserved for future VIP-style feature gating.",
            example = "false",
            nullable = true)
    private Boolean verified;
}
