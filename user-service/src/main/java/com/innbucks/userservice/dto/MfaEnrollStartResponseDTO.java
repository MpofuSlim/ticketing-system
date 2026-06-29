package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code POST /auth/mfa/enroll/start}. The FE renders the QR PNG
 * (or the {@code otpauthUri} as a fallback for users who type the secret in
 * manually) and prompts for the first 6-digit code.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(name = "MfaEnrollStartResponse",
        description = "Provisioning data for the authenticator app + the same mfaToken to echo to /complete.")
public class MfaEnrollStartResponseDTO {

    @Schema(description = "Base32 TOTP shared secret. Shown so users who can't scan a QR can type it in. "
            + "After /complete the FE should discard this and never re-display it.",
            example = "JBSWY3DPEHPK3PXP")
    private String secret;

    @Schema(description = "Standard otpauth:// provisioning URI — both Google Authenticator and Authy "
            + "accept it as either a QR payload or a manual paste.",
            example = "otpauth://totp/InnBucks:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=InnBucks")
    private String otpauthUri;

    @Schema(description = "Base64-encoded PNG of the QR code the FE should render on-screen.",
            example = "iVBORw0KGgoAAAANSUhEUgAA...")
    private String qrPngBase64;

    @Schema(description = "Echo of the input mfaToken — same value the FE will post on /enroll/complete.")
    private String mfaToken;
}
