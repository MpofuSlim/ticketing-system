package com.innbucks.userservice.dto;

import lombok.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

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

    @Schema(description = "Principal's role.",
            example = "CUSTOMER",
            allowableValues = {"CUSTOMER", "TENANT", "ADMIN", "SYSTEM_MANAGER", "MERCHANT_ADMIN", "SHOP_ADMIN", "SHOP_USER"})
    private String role;

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
