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

    @Schema(description = "The organization this session acts for — same as `organizationId` on the "
            + "login response. Absent when the account belongs to none, or to several and none is chosen.",
            example = "7b1e2c4d-9f3a-4e5b-8c6d-0a1b2c3d4e5f", nullable = true)
    private java.util.UUID organizationId;

    @Schema(description = "The caller's role in `organizationId`: OWNER, ADMIN or STAFF.",
            example = "ADMIN", nullable = true)
    private String organizationRole;

    @Schema(description = "The ACTIVE products of `organizationId`, as on the login response.",
            example = "[\"loyalty\", \"marketplace\"]", nullable = true)
    private List<String> organizationProducts;

    @Schema(description = "`true` when the account belongs to several organizations and none is chosen: "
            + "show the picker and call POST /auth/organization-context. Absent otherwise.",
            example = "true", nullable = true)
    private Boolean organizationSelectionRequired;

    public MfaEnrollCompleteResponseDTO(String token, String refreshToken, List<String> backupCodes) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.backupCodes = backupCodes;
    }

    /**
     * The enrolment response for a freshly issued session. Enrolment is where
     * every staff account's FIRST sign-in ends, so it must carry the same
     * organization scope as a login or refresh response — without it the
     * client learns neither which business the session acts for nor that it
     * must pick one.
     */
    public static MfaEnrollCompleteResponseDTO from(AuthResponseDTO session, List<String> backupCodes) {
        MfaEnrollCompleteResponseDTO dto = new MfaEnrollCompleteResponseDTO(
                session.getToken(), session.getRefreshToken(), backupCodes);
        dto.setOrganizationId(session.getOrganizationId());
        dto.setOrganizationRole(session.getOrganizationRole());
        dto.setOrganizationProducts(session.getOrganizationProducts());
        dto.setOrganizationSelectionRequired(session.getOrganizationSelectionRequired());
        return dto;
    }
}
