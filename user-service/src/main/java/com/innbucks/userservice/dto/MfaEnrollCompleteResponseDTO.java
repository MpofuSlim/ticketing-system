package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response of {@code POST /auth/mfa/enroll/complete}: real access + refresh
 * tokens (same wire shape the post-login flow uses, so the FE handles them the
 * same) PLUS the one-and-only-time list of single-use backup codes. The FE
 * MUST tell the user to save / print these — they're never shown again.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MfaEnrollCompleteResponse",
        description = "Real tokens + the one-time backup-code list.")
public class MfaEnrollCompleteResponseDTO {

    @Schema(description = "JWT access token. Sent as Authorization: Bearer <token> on subsequent requests.")
    private String token;

    @Schema(description = "Long-lived JWT refresh token. Used ONLY at POST /auth/refresh.")
    private String refreshToken;

    @Schema(description = "10 single-use recovery codes. SHOWN ONCE — tell the user to write them down. "
            + "Each can be entered in place of a TOTP code on /auth/login/mfa exactly one time.",
            example = "[\"X4Q7-K9F2-A3B1-M8H6\", \"...\"]")
    private List<String> backupCodes;
}
