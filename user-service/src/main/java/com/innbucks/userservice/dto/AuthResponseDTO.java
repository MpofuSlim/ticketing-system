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

    @Schema(description = "The user's system ID.", example = "42")
    private Long userId;

    @Schema(description = "JWT access token. Null when `mfaRequired=true` or on registration responses.",
            nullable = true)
    private String token;

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

    @Schema(description = "True when the account has MFA enabled and no OTP was supplied on this request. " +
            "In that case, `token` is null and the client must re-submit the login including `otpCode`.",
            example = "false")
    private boolean mfaRequired;

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
